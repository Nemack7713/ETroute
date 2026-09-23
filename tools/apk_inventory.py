#!/usr/bin/env python3
"""Read-only APK inventory scanner for ETroute/LocalForge.

The scanner is standard-library only and does not require AAPT2 for structural
inventory. When an explicit absolute AAPT2 path is supplied, dump badging
metadata is added as an enrichment layer.

No ADB, shell, package installation, APK mutation, signing, or extraction is
performed.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import zipfile
from collections import Counter
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Sequence

VERSION = "2.0.0"
SCHEMA_VERSION = 1
MAX_ELF_BYTES = 128 * 1024 * 1024
APK_SIG_MAGIC = b"APK Sig Block 42"

_PAIR_RE = re.compile(r"([A-Za-z0-9_.\-]+)='((?:[^'\\]|\\.)*)'")
_DEX_RE = re.compile(r"^classes(?:([2-9][0-9]*))?\.dex$")
_NATIVE_RE = re.compile(r"^lib/([^/]+)/([^/]+\.so)$")

MACHINE_NAMES = {
    3: "x86",
    40: "ARM",
    62: "x86_64",
    183: "AARCH64",
}


class InventoryError(RuntimeError):
    pass


def sha256_stream(stream, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    while True:
        block = stream.read(chunk_size)
        if not block:
            break
        digest.update(block)
    return digest.hexdigest()


def sha256_file(path: Path) -> str:
    with path.open("rb") as stream:
        return sha256_stream(stream)


def _unescape(value: str) -> str:
    return value.replace("\\'", "'").replace("\\\\", "\\")


def _to_int(value: str | None) -> int | None:
    try:
        return int(value) if value is not None else None
    except ValueError:
        return None


def parse_badging(text: str) -> dict[str, Any]:
    info: dict[str, Any] = {
        "package": None,
        "version_code": None,
        "version_name": None,
        "platform_build_version_name": None,
        "compile_sdk_version": None,
        "min_sdk": None,
        "target_sdk": None,
        "max_sdk": None,
        "app_label": None,
        "launchable_activity": None,
        "debuggable": False,
        "permissions": [],
        "features": [],
        "libraries": [],
        "locales": [],
        "densities": [],
        "native_code": [],
        "supports_screens": [],
    }

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if ":" not in line:
            if line == "application-debuggable":
                info["debuggable"] = True
            continue

        key, _, rest = line.partition(":")
        pairs = {k: _unescape(v) for k, v in _PAIR_RE.findall(rest)}
        bare = re.findall(r"'((?:[^'\\]|\\.)*)'", _PAIR_RE.sub("", rest))
        bare = [_unescape(value) for value in bare]

        if key == "package":
            info["package"] = pairs.get("name")
            info["version_code"] = _to_int(pairs.get("versionCode"))
            info["version_name"] = pairs.get("versionName")
            info["platform_build_version_name"] = pairs.get("platformBuildVersionName")
            info["compile_sdk_version"] = pairs.get("compileSdkVersion")
        elif key == "sdkVersion":
            info["min_sdk"] = bare[0] if bare else None
        elif key == "targetSdkVersion":
            info["target_sdk"] = bare[0] if bare else None
        elif key == "maxSdkVersion":
            info["max_sdk"] = bare[0] if bare else None
        elif key == "uses-permission":
            name = pairs.get("name")
            if name and name not in info["permissions"]:
                info["permissions"].append(name)
        elif key == "uses-feature":
            name = pairs.get("name")
            if name:
                info["features"].append({
                    "name": name,
                    "required": pairs.get("required", "true"),
                })
        elif key == "uses-library":
            name = pairs.get("name")
            if name and name not in info["libraries"]:
                info["libraries"].append(name)
        elif key == "application-label" and info["app_label"] is None and bare:
            info["app_label"] = bare[0]
        elif key == "launchable-activity" and info["launchable_activity"] is None:
            info["launchable_activity"] = pairs.get("name")
        elif key == "locales":
            info["locales"] = bare
        elif key == "densities":
            info["densities"] = bare
        elif key == "native-code":
            info["native_code"] = bare
        elif key == "supports-screens":
            info["supports_screens"] = bare

    return info


def resolve_aapt2(explicit: str | None) -> str | None:
    if explicit is None:
        return None

    path = os.path.realpath(os.path.expanduser(explicit))
    if not os.path.isabs(path):
        raise InventoryError("AAPT2 path must be absolute")
    if not os.path.isfile(path):
        raise InventoryError(f"AAPT2 does not exist: {path}")
    if not os.access(path, os.X_OK):
        raise InventoryError(f"AAPT2 is not executable: {path}")
    return path


def aapt2_version(aapt2: str, timeout: int = 30) -> str:
    try:
        proc = subprocess.run(
            [aapt2, "version"],
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise InventoryError(f"cannot execute AAPT2: {exc}") from exc

    lines = (proc.stdout + proc.stderr).strip().splitlines()
    if proc.returncode != 0 or not lines:
        raise InventoryError(
            f"AAPT2 version failed: {lines[-1] if lines else 'no output'}"
        )
    return lines[0].strip()


def run_badging(aapt2: str, apk: Path, timeout: int) -> dict[str, Any]:
    try:
        proc = subprocess.run(
            [aapt2, "dump", "badging", str(apk)],
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": f"AAPT2 timed out after {timeout}s"}
    except OSError as exc:
        return {"ok": False, "error": f"AAPT2 exec failed: {exc}"}

    if proc.returncode != 0:
        lines = (proc.stderr or proc.stdout or "").strip().splitlines()
        return {
            "ok": False,
            "error": f"AAPT2 exit {proc.returncode}: {lines[-1] if lines else 'no output'}",
        }

    parsed = parse_badging(proc.stdout)
    if not parsed.get("package"):
        return {"ok": False, "error": "badging parse failed: no package line"}
    return {"ok": True, **parsed}


def unsafe_zip_path(name: str) -> bool:
    if "\\" in name or name.startswith("/") or "\x00" in name:
        return True
    parts = PurePosixPath(name).parts
    if ".." in parts:
        return True
    if parts and re.match(r"^[A-Za-z]:$", parts[0]):
        return True
    return False


def zip_data_offset(apk_file, info: zipfile.ZipInfo) -> int | None:
    try:
        apk_file.seek(info.header_offset)
        header = apk_file.read(30)
        if len(header) != 30 or header[:4] != b"PK\x03\x04":
            return None
        name_len, extra_len = struct.unpack_from("<HH", header, 26)
        return info.header_offset + 30 + name_len + extra_len
    except OSError:
        return None


def detect_apk_signing_block(path: Path) -> dict[str, Any]:
    size = path.stat().st_size
    if size < 22:
        return {"present": False}

    with path.open("rb") as stream:
        tail_size = min(size, 65557)
        stream.seek(size - tail_size)
        tail = stream.read(tail_size)
        index = tail.rfind(b"PK\x05\x06")
        if index < 0 or index + 22 > len(tail):
            return {"present": False, "error": "EOCD not found"}

        central_directory_offset = struct.unpack_from("<I", tail, index + 16)[0]
        if central_directory_offset < 24:
            return {"present": False}

        stream.seek(central_directory_offset - 24)
        footer = stream.read(24)
        if len(footer) != 24 or footer[8:] != APK_SIG_MAGIC:
            return {"present": False}

        block_size = struct.unpack_from("<Q", footer, 0)[0]
        block_start = central_directory_offset - (block_size + 8)
        if block_start < 0:
            return {"present": False, "error": "invalid signing block size"}

        stream.seek(block_start)
        header = stream.read(8)
        if len(header) != 8:
            return {"present": False, "error": "truncated signing block"}
        header_size = struct.unpack("<Q", header)[0]
        if header_size != block_size:
            return {"present": False, "error": "signing block size mismatch"}

        return {
            "present": True,
            "offset": block_start,
            "size_bytes": block_size + 8,
        }


def _u16(data: bytes, offset: int, endian: str) -> int:
    return struct.unpack_from(endian + "H", data, offset)[0]


def _u32(data: bytes, offset: int, endian: str) -> int:
    return struct.unpack_from(endian + "I", data, offset)[0]


def inspect_elf(data: bytes) -> dict[str, Any]:
    if len(data) < 52 or data[:4] != b"\x7fELF":
        return {"valid": False, "error": "not ELF"}

    elf_class = data[4]
    data_encoding = data[5]
    if data_encoding == 1:
        endian = "<"
        endianness = "little"
    elif data_encoding == 2:
        endian = ">"
        endianness = "big"
    else:
        return {"valid": False, "error": "invalid ELF data encoding"}

    if elf_class == 2:
        if len(data) < 64:
            return {"valid": False, "error": "truncated ELF64 header"}
        elf_type = _u16(data, 16, endian)
        machine = _u16(data, 18, endian)
        phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        phentsize = _u16(data, 54, endian)
        phnum = _u16(data, 56, endian)
        ph_fmt = endian + "IIQQQQQQ"
        ph_min = 56
        dyn_fmt = endian + "qQ"
        dyn_size = 16
    elif elf_class == 1:
        elf_type = _u16(data, 16, endian)
        machine = _u16(data, 18, endian)
        phoff = _u32(data, 28, endian)
        phentsize = _u16(data, 42, endian)
        phnum = _u16(data, 44, endian)
        ph_fmt = endian + "IIIIIIII"
        ph_min = 32
        dyn_fmt = endian + "iI"
        dyn_size = 8
    else:
        return {"valid": False, "error": f"unsupported ELF class {elf_class}"}

    loads: list[tuple[int, int, int]] = []
    dynamic: tuple[int, int] | None = None
    interpreter: str | None = None
    gnu_relro = False
    nx_stack: bool | None = None

    for index in range(phnum):
        offset = phoff + index * phentsize
        if phentsize < ph_min or offset + ph_min > len(data):
            break
        fields = struct.unpack_from(ph_fmt, data, offset)
        if elf_class == 2:
            p_type, p_flags, p_offset, p_vaddr, _, p_filesz, _, _ = fields
        else:
            p_type, p_offset, p_vaddr, _, p_filesz, _, p_flags, _ = fields

        if p_type == 1:
            loads.append((p_offset, p_vaddr, p_filesz))
        elif p_type == 2:
            dynamic = (p_offset, p_filesz)
        elif p_type == 3:
            raw = data[p_offset:p_offset + p_filesz].split(b"\x00", 1)[0]
            interpreter = raw.decode("utf-8", "replace")
        elif p_type == 0x6474E552:
            gnu_relro = True
        elif p_type == 0x6474E551:
            nx_stack = (p_flags & 1) == 0

    def vaddr_to_offset(address: int) -> int | None:
        for file_offset, virtual_address, file_size in loads:
            if virtual_address <= address < virtual_address + file_size:
                return file_offset + (address - virtual_address)
        return None

    needed_offsets: list[int] = []
    strtab_addr: int | None = None
    strtab_size: int | None = None
    rpath_offset: int | None = None
    runpath_offset: int | None = None
    bind_now = False
    pie_flag = False

    if dynamic:
        dynamic_offset, dynamic_length = dynamic
        end = min(len(data), dynamic_offset + dynamic_length)
        for offset in range(dynamic_offset, end, dyn_size):
            if offset + dyn_size > len(data):
                break
            tag, value = struct.unpack_from(dyn_fmt, data, offset)
            if tag == 0:
                break
            if tag == 1:
                needed_offsets.append(value)
            elif tag == 5:
                strtab_addr = value
            elif tag == 10:
                strtab_size = value
            elif tag == 15:
                rpath_offset = value
            elif tag == 24:
                bind_now = True
            elif tag == 29:
                runpath_offset = value
            elif tag == 30 and value & 0x8:
                bind_now = True
            elif tag == 0x6FFFFFFB and value & 0x08000000:
                pie_flag = True

    needed: list[str] = []
    rpath = None
    runpath = None
    if strtab_addr is not None and strtab_size is not None:
        strtab = vaddr_to_offset(strtab_addr)
        if strtab is not None and strtab + strtab_size <= len(data):
            limit = strtab + strtab_size

            def string_at(relative: int | None) -> str | None:
                if relative is None:
                    return None
                start = strtab + relative
                if not (strtab <= start < limit):
                    return None
                end = data.find(b"\x00", start, limit)
                if end < 0:
                    return None
                return data[start:end].decode("utf-8", "replace")

            needed = [value for value in (string_at(x) for x in needed_offsets) if value]
            rpath = string_at(rpath_offset)
            runpath = string_at(runpath_offset)

    return {
        "valid": True,
        "class": 64 if elf_class == 2 else 32,
        "endianness": endianness,
        "type": elf_type,
        "machine": machine,
        "machine_name": MACHINE_NAMES.get(machine, f"EM_{machine}"),
        "interpreter": interpreter,
        "dt_needed": sorted(set(needed)),
        "rpath": rpath,
        "runpath": runpath,
        "pie": elf_type == 3 and (pie_flag or interpreter is not None),
        "gnu_relro": gnu_relro,
        "bind_now": bind_now,
        "nx_stack": nx_stack,
    }


def inspect_zip(path: Path) -> dict[str, Any]:
    result: dict[str, Any] = {
        "valid_zip": False,
        "entry_count": 0,
        "duplicate_entries": [],
        "unsafe_paths": [],
        "compressed_bytes": 0,
        "uncompressed_bytes": 0,
        "manifest": None,
        "resources_arsc": None,
        "dex": [],
        "native_libraries": [],
        "v1_signature_entries": [],
        "signing_block": detect_apk_signing_block(path),
    }

    try:
        with path.open("rb") as apk_file, zipfile.ZipFile(apk_file) as archive:
            infos = archive.infolist()
            result["valid_zip"] = True
            result["entry_count"] = len(infos)
            names = [info.filename for info in infos]
            counts = Counter(names)
            result["duplicate_entries"] = sorted(
                name for name, count in counts.items() if count > 1
            )
            result["unsafe_paths"] = sorted(
                name for name in names if unsafe_zip_path(name)
            )
            result["compressed_bytes"] = sum(info.compress_size for info in infos)
            result["uncompressed_bytes"] = sum(info.file_size for info in infos)

            for info in infos:
                name = info.filename
                upper = name.upper()
                if name == "AndroidManifest.xml":
                    with archive.open(info) as stream:
                        result["manifest"] = {
                            "size_bytes": info.file_size,
                            "sha256": sha256_stream(stream),
                            "compression": info.compress_type,
                        }
                elif name == "resources.arsc":
                    offset = zip_data_offset(apk_file, info)
                    with archive.open(info) as stream:
                        result["resources_arsc"] = {
                            "size_bytes": info.file_size,
                            "sha256": sha256_stream(stream),
                            "compression": info.compress_type,
                            "data_offset": offset,
                            "aligned_4k": offset is not None and offset % 4096 == 0,
                            "aligned_16k": offset is not None and offset % 16384 == 0,
                        }
                elif _DEX_RE.fullmatch(name):
                    with archive.open(info) as stream:
                        result["dex"].append({
                            "name": name,
                            "size_bytes": info.file_size,
                            "sha256": sha256_stream(stream),
                            "compression": info.compress_type,
                        })
                else:
                    native = _NATIVE_RE.fullmatch(name)
                    if native:
                        abi, library_name = native.groups()
                        offset = zip_data_offset(apk_file, info)
                        record: dict[str, Any] = {
                            "path": name,
                            "abi": abi,
                            "name": library_name,
                            "size_bytes": info.file_size,
                            "compression": info.compress_type,
                            "data_offset": offset,
                            "aligned_4k": offset is not None and offset % 4096 == 0,
                            "aligned_16k": offset is not None and offset % 16384 == 0,
                        }
                        if info.file_size <= MAX_ELF_BYTES:
                            payload = archive.read(info)
                            record["sha256"] = hashlib.sha256(payload).hexdigest()
                            record["elf"] = inspect_elf(payload)
                        else:
                            record["elf"] = {
                                "valid": False,
                                "error": f"ELF exceeds {MAX_ELF_BYTES} byte inspection cap",
                            }
                        result["native_libraries"].append(record)

                if upper.startswith("META-INF/") and (
                    upper.endswith(".RSA")
                    or upper.endswith(".DSA")
                    or upper.endswith(".EC")
                    or upper.endswith(".SF")
                    or upper == "META-INF/MANIFEST.MF"
                ):
                    result["v1_signature_entries"].append(name)

            result["dex"].sort(key=lambda x: x["name"])
            result["native_libraries"].sort(key=lambda x: x["path"])
            result["v1_signature_entries"].sort()
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        result["error"] = str(exc)

    return result


def scan_apk(
    apk_path: Path,
    *,
    aapt2: str | None = None,
    timeout: int = 120,
) -> dict[str, Any]:
    path = apk_path.expanduser().resolve()
    entry: dict[str, Any] = {
        "file": path.name,
        "path": str(path),
        "ok": False,
    }

    try:
        entry["size_bytes"] = path.stat().st_size
        entry["sha256"] = sha256_file(path)
    except OSError as exc:
        entry["error"] = f"file access failed: {exc}"
        return entry

    structure = inspect_zip(path)
    entry["structure"] = structure
    if not structure.get("valid_zip"):
        entry["error"] = structure.get("error", "invalid ZIP/APK")
        return entry

    if aapt2:
        entry["aapt2_badging"] = run_badging(aapt2, path, timeout)

    structural_failures = []
    if structure["unsafe_paths"]:
        structural_failures.append("unsafe ZIP paths")
    if structure["duplicate_entries"]:
        structural_failures.append("duplicate ZIP entries")
    if structure["manifest"] is None:
        structural_failures.append("AndroidManifest.xml missing")

    entry["structural_warnings"] = structural_failures
    entry["ok"] = not structural_failures
    return entry


def iter_apks(paths: Sequence[str], directory: str | None, recursive: bool) -> Iterable[Path]:
    seen: set[str] = set()

    for raw in paths:
        path = Path(raw).expanduser().resolve()
        if path.suffix.lower() == ".apk" and path.is_file() and str(path) not in seen:
            seen.add(str(path))
            yield path

    if directory:
        root = Path(directory).expanduser().resolve()
        iterator = root.rglob("*.apk") if recursive else root.glob("*.apk")
        for path in sorted(iterator):
            resolved = path.resolve()
            if resolved.is_file() and str(resolved) not in seen:
                seen.add(str(resolved))
                yield resolved


CSV_FIELDS = [
    "file",
    "path",
    "ok",
    "size_bytes",
    "sha256",
    "package",
    "version_name",
    "version_code",
    "min_sdk",
    "target_sdk",
    "dex_count",
    "native_library_count",
    "native_abis",
    "signing_block_present",
    "v1_signature_entry_count",
    "unsafe_path_count",
    "duplicate_entry_count",
    "error",
]


def flatten(entry: dict[str, Any]) -> dict[str, Any]:
    structure = entry.get("structure") or {}
    badging = entry.get("aapt2_badging") or {}
    native = structure.get("native_libraries") or []

    return {
        "file": entry.get("file", ""),
        "path": entry.get("path", ""),
        "ok": entry.get("ok", False),
        "size_bytes": entry.get("size_bytes", ""),
        "sha256": entry.get("sha256", ""),
        "package": badging.get("package") or "",
        "version_name": badging.get("version_name") or "",
        "version_code": badging.get("version_code") if badging.get("version_code") is not None else "",
        "min_sdk": badging.get("min_sdk") or "",
        "target_sdk": badging.get("target_sdk") or "",
        "dex_count": len(structure.get("dex") or []),
        "native_library_count": len(native),
        "native_abis": ";".join(sorted({item["abi"] for item in native})),
        "signing_block_present": bool((structure.get("signing_block") or {}).get("present")),
        "v1_signature_entry_count": len(structure.get("v1_signature_entries") or []),
        "unsafe_path_count": len(structure.get("unsafe_paths") or []),
        "duplicate_entry_count": len(structure.get("duplicate_entries") or []),
        "error": entry.get("error") or "",
    }


def write_output(
    entries: list[dict[str, Any]],
    *,
    fmt: str,
    output: str | None,
    aapt2_path: str | None,
    aapt2_version_string: str | None,
) -> None:
    if fmt == "json":
        payload = {
            "schemaVersion": SCHEMA_VERSION,
            "scannerVersion": VERSION,
            "aapt2": {
                "path": aapt2_path,
                "version": aapt2_version_string,
            } if aapt2_path else None,
            "apks": entries,
        }
        rendered = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
        if output:
            Path(output).write_text(rendered, encoding="utf-8")
        else:
            sys.stdout.write(rendered)
        return

    handle = open(output, "w", encoding="utf-8", newline="") if output else sys.stdout
    close = output is not None
    try:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS, extrasaction="ignore")
        writer.writeheader()
        for entry in entries:
            writer.writerow(flatten(entry))
    finally:
        if close:
            handle.close()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_argument_group("input")
    source.add_argument("apks", nargs="*", help="APK files to scan")
    source.add_argument("--dir", help="directory containing APKs")
    source.add_argument("--recursive", action="store_true")

    enrichment = parser.add_argument_group("optional AAPT2 enrichment")
    enrichment.add_argument(
        "--aapt2",
        help="absolute path to already-admitted/verified aapt2 executable",
    )
    enrichment.add_argument("--aapt2-timeout", type=int, default=120)

    output = parser.add_argument_group("output")
    output.add_argument("--format", choices=["json", "csv"], default="json")
    output.add_argument("--output", help="output file; default is stdout")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.apks and not args.dir:
        print("nothing to scan: give APK files or --dir", file=sys.stderr)
        return 1

    try:
        aapt2 = resolve_aapt2(args.aapt2)
        aapt2_ver = aapt2_version(aapt2) if aapt2 else None
    except InventoryError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    targets = list(iter_apks(args.apks, args.dir, args.recursive))
    if not targets:
        print("no APKs found", file=sys.stderr)
        return 1

    entries = [
        scan_apk(path, aapt2=aapt2, timeout=args.aapt2_timeout)
        for path in targets
    ]
    write_output(
        entries,
        fmt=args.format,
        output=args.output,
        aapt2_path=aapt2,
        aapt2_version_string=aapt2_ver,
    )

    failed = sum(1 for entry in entries if not entry["ok"])
    print(f"scanned {len(entries)} APKs, {failed} failed structural acceptance", file=sys.stderr)
    return 2 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
