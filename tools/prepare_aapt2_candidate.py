#!/usr/bin/env python3
"""Prepare a structurally verified Android-native AAPT2 RuntimePack candidate.

The tool verifies ELF64/AArch64 identity, inventories the Android dynamic
linker and DT_NEEDED entries, applies a conservative Android linkage policy,
copies the candidate into immutable staging layout, and writes evidence.

It does not run the binary, grant trust, or publish a RuntimePack generation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Sequence

ELF_MAGIC = b"\x7fELF"
ELFCLASS64 = 2
ELFDATA2LSB = 1
ET_DYN = 3
EM_AARCH64 = 183
PT_LOAD = 1
PT_DYNAMIC = 2
PT_INTERP = 3
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10

AAPT2_PACK_ID = "org.etroute.aapt2"
AAPT2_TOOL_ID = "aapt2"
REFERENCE_REPOSITORY = "rendiix/termux-apktool"
ALLOWED_INTERPRETERS = {None, "/system/bin/linker64"}
FORBIDDEN_NEEDED = {
    "libc.so.6",
    "libdl.so.2",
    "libm.so.6",
    "libpthread.so.0",
    "librt.so.1",
    "libstdc++.so.6",
    "libgcc_s.so.1",
}
NEEDED_NAME_RE = re.compile(r"^[A-Za-z0-9_+.-]+\.so(?:\.[0-9]+)*$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class Aapt2CandidateError(RuntimeError):
    pass


@dataclass(frozen=True)
class LoadSegment:
    file_offset: int
    virtual_address: int
    file_size: int
    memory_size: int


@dataclass(frozen=True)
class ElfEvidence:
    elf_class: int
    little_endian: bool
    elf_type: int
    machine: int
    entry_point: int
    interpreter: str | None
    needed: tuple[str, ...]
    file_size: int
    sha256: str
    linkage_policy_ok: bool
    linkage_policy_violations: tuple[str, ...]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(128 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _read_c_string(data: bytes, start: int, limit: int) -> str:
    if start < 0 or start >= limit or limit > len(data):
        raise Aapt2CandidateError("ELF string offset is outside file bounds")
    end = data.find(b"\x00", start, limit)
    if end < 0:
        raise Aapt2CandidateError("ELF string was not NUL-terminated")
    try:
        return data[start:end].decode("utf-8")
    except UnicodeDecodeError as exc:
        raise Aapt2CandidateError("ELF string table contained invalid UTF-8") from exc


def _vaddr_to_offset(address: int, loads: Sequence[LoadSegment]) -> int:
    for segment in loads:
        start = segment.virtual_address
        end = start + segment.file_size
        if start <= address < end:
            return segment.file_offset + (address - start)
    raise Aapt2CandidateError(
        f"ELF virtual address 0x{address:x} is not backed by a PT_LOAD file range"
    )


def inspect_elf(path: Path) -> ElfEvidence:
    path = path.expanduser().resolve()
    if not path.is_file():
        raise Aapt2CandidateError(f"AAPT2 candidate does not exist: {path}")

    data = path.read_bytes()
    if len(data) < 64:
        raise Aapt2CandidateError("AAPT2 candidate is too small to be ELF64")
    if data[:4] != ELF_MAGIC:
        raise Aapt2CandidateError("AAPT2 candidate is not an ELF file")
    if data[4] != ELFCLASS64:
        raise Aapt2CandidateError("AAPT2 candidate must be ELF64")
    if data[5] != ELFDATA2LSB:
        raise Aapt2CandidateError("AAPT2 candidate must be little-endian ELF")

    try:
        (
            elf_type,
            machine,
            _version,
            entry_point,
            program_header_offset,
            _section_header_offset,
            _flags,
            header_size,
            program_header_size,
            program_header_count,
            _section_header_size,
            _section_header_count,
            _section_name_index,
        ) = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    except struct.error as exc:
        raise Aapt2CandidateError("ELF64 header is truncated") from exc

    if header_size < 64:
        raise Aapt2CandidateError("ELF64 header size is invalid")
    if elf_type != ET_DYN:
        raise Aapt2CandidateError(
            f"AAPT2 candidate must be PIE/ET_DYN, got e_type={elf_type}"
        )
    if machine != EM_AARCH64:
        raise Aapt2CandidateError(
            f"AAPT2 candidate must be AArch64, got e_machine={machine}"
        )
    if program_header_size < 56:
        raise Aapt2CandidateError("ELF64 program-header size is invalid")

    loads: list[LoadSegment] = []
    interpreter: str | None = None
    dynamic_offset: int | None = None
    dynamic_size = 0

    for index in range(program_header_count):
        offset = program_header_offset + index * program_header_size
        if offset < 0 or offset + 56 > len(data):
            raise Aapt2CandidateError("ELF64 program headers exceed file bounds")

        (
            segment_type,
            _segment_flags,
            segment_offset,
            segment_vaddr,
            _segment_paddr,
            segment_file_size,
            segment_memory_size,
            _segment_align,
        ) = struct.unpack_from("<IIQQQQQQ", data, offset)

        if segment_offset + segment_file_size > len(data):
            raise Aapt2CandidateError(
                f"ELF segment {index} exceeds file bounds"
            )

        if segment_type == PT_LOAD:
            loads.append(
                LoadSegment(
                    file_offset=segment_offset,
                    virtual_address=segment_vaddr,
                    file_size=segment_file_size,
                    memory_size=segment_memory_size,
                )
            )
        elif segment_type == PT_INTERP:
            raw = data[segment_offset:segment_offset + segment_file_size]
            raw = raw.split(b"\x00", 1)[0]
            try:
                interpreter = raw.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise Aapt2CandidateError("PT_INTERP is not valid UTF-8") from exc
        elif segment_type == PT_DYNAMIC:
            dynamic_offset = segment_offset
            dynamic_size = segment_file_size

    needed: list[str] = []
    if dynamic_offset is not None:
        strtab_address: int | None = None
        strtab_size: int | None = None
        needed_offsets: list[int] = []

        if dynamic_size % 16 != 0:
            raise Aapt2CandidateError("PT_DYNAMIC size is not Elf64_Dyn aligned")

        for offset in range(
            dynamic_offset,
            dynamic_offset + dynamic_size,
            16,
        ):
            try:
                tag, value = struct.unpack_from("<qQ", data, offset)
            except struct.error as exc:
                raise Aapt2CandidateError("PT_DYNAMIC entry is truncated") from exc
            if tag == DT_NULL:
                break
            if tag == DT_NEEDED:
                needed_offsets.append(value)
            elif tag == DT_STRTAB:
                strtab_address = value
            elif tag == DT_STRSZ:
                strtab_size = value

        if needed_offsets:
            if strtab_address is None or strtab_size is None:
                raise Aapt2CandidateError(
                    "DT_NEEDED exists without DT_STRTAB/DT_STRSZ"
                )
            strtab_offset = _vaddr_to_offset(strtab_address, loads)
            strtab_limit = strtab_offset + strtab_size
            if strtab_limit > len(data):
                raise Aapt2CandidateError("dynamic string table exceeds file bounds")
            for needed_offset in needed_offsets:
                needed.append(
                    _read_c_string(
                        data,
                        strtab_offset + needed_offset,
                        strtab_limit,
                    )
                )

    violations = evaluate_android_linkage(interpreter, needed)
    return ElfEvidence(
        elf_class=ELFCLASS64,
        little_endian=True,
        elf_type=elf_type,
        machine=machine,
        entry_point=entry_point,
        interpreter=interpreter,
        needed=tuple(sorted(set(needed))),
        file_size=len(data),
        sha256=sha256_file(path),
        linkage_policy_ok=not violations,
        linkage_policy_violations=tuple(violations),
    )


def evaluate_android_linkage(
    interpreter: str | None,
    needed: Sequence[str],
) -> list[str]:
    violations: list[str] = []
    if interpreter not in ALLOWED_INTERPRETERS:
        violations.append(f"unexpected interpreter: {interpreter!r}")

    for library in needed:
        if "/" in library or "\\" in library:
            violations.append(f"DT_NEEDED contains path: {library!r}")
            continue
        if library in FORBIDDEN_NEEDED:
            violations.append(f"desktop/glibc dependency is forbidden: {library}")
            continue
        if NEEDED_NAME_RE.fullmatch(library) is None:
            violations.append(f"unexpected DT_NEEDED name: {library!r}")

    return violations


def build_candidate_manifest(
    *,
    source_repository: str,
    source_revision: str,
    artifact_sha256: str,
    artifact_size: int,
    min_api: int,
) -> dict[str, Any]:
    if SHA256_RE.fullmatch(artifact_sha256) is None:
        raise Aapt2CandidateError("artifact_sha256 must be lowercase SHA-256 hex")
    if not source_repository.strip():
        raise Aapt2CandidateError("source_repository cannot be blank")
    if not source_revision.strip():
        raise Aapt2CandidateError("source_revision cannot be blank")
    if min_api <= 0:
        raise Aapt2CandidateError("min_api must be positive")

    return {
        "schemaVersion": 1,
        "packId": AAPT2_PACK_ID,
        "version": source_revision,
        "provenance": "EXTERNAL_VERIFIED",
        "minApi": min_api,
        "abis": ["arm64-v8a"],
        "source": {
            "repository": source_repository,
            "revision": source_revision,
            "assetName": "aapt2",
            "assetSha256": artifact_sha256,
        },
        "artifacts": [
            {
                "path": "artifacts/aapt2",
                "sha256": artifact_sha256,
                "sizeBytes": artifact_size,
                "executable": True,
            }
        ],
        "dependencies": [],
        "tools": [
            {
                "toolId": AAPT2_TOOL_ID,
                "kind": "NATIVE_EXECUTABLE",
                "artifactPath": "artifacts/aapt2",
            }
        ],
    }


def _write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(value, indent=2, sort_keys=True) + "\n"
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(rendered, encoding="utf-8")
    os.replace(temporary, path)


def prepare_candidate(
    binary: Path,
    destination: Path,
    *,
    source_repository: str,
    source_revision: str,
    min_api: int = 24,
) -> dict[str, Any]:
    binary = binary.expanduser().resolve()
    evidence = inspect_elf(binary)
    if not evidence.linkage_policy_ok:
        raise Aapt2CandidateError(
            "AAPT2 linkage policy failed: "
            + "; ".join(evidence.linkage_policy_violations)
        )

    destination = destination.expanduser().resolve()
    if destination.exists():
        raise Aapt2CandidateError(
            f"destination already exists; refusing mutable replacement: {destination}"
        )

    staging = destination.with_name(f".{destination.name}.staging")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    try:
        artifact = staging / "artifacts" / "aapt2"
        artifact.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(binary, artifact)
        artifact.chmod(0o700)

        copied = inspect_elf(artifact)
        if copied.sha256 != evidence.sha256:
            raise Aapt2CandidateError("AAPT2 artifact changed during staging")

        manifest = build_candidate_manifest(
            source_repository=source_repository,
            source_revision=source_revision,
            artifact_sha256=copied.sha256,
            artifact_size=copied.file_size,
            min_api=min_api,
        )
        payload = {
            "schemaVersion": 1,
            "status": "STRUCTURALLY_VERIFIED_CANDIDATE",
            "trustGranted": False,
            "published": False,
            "runtimeProbePassed": False,
            "compileSmokePassed": False,
            "linkSmokePassed": False,
            "compatibilityReference": REFERENCE_REPOSITORY,
            "sourceRepository": source_repository,
            "sourceRevision": source_revision,
            "elf": asdict(copied),
        }

        _write_json_atomic(staging / "candidate-manifest.json", manifest)
        _write_json_atomic(staging / "candidate-evidence.json", payload)
        os.replace(staging, destination)
        return payload
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("binary", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--source-repository", required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--min-api", type=int, default=24)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        payload = prepare_candidate(
            args.binary,
            args.destination,
            source_repository=args.source_repository,
            source_revision=args.source_revision,
            min_api=args.min_api,
        )
    except Aapt2CandidateError as exc:
        print(f"AAPT2_CANDIDATE FAILED: {exc}", file=os.sys.stderr)
        return 2

    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
