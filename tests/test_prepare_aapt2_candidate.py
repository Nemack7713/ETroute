#!/usr/bin/env python3
from __future__ import annotations

import struct
import tempfile
import unittest
from pathlib import Path

from tools import prepare_aapt2_candidate as candidate


def build_minimal_elf64_aarch64(
    path: Path,
    *,
    interpreter: str | None = "/system/bin/linker64",
    needed: tuple[str, ...] = ("libc.so", "libdl.so"),
) -> None:
    header_size = 64
    program_header_size = 56
    program_headers = 3 if interpreter is not None else 2
    phoff = header_size

    interp_bytes = b""
    interp_offset = header_size + program_header_size * program_headers
    if interpreter is not None:
        interp_bytes = interpreter.encode("utf-8") + b"\x00"

    dyn_offset = interp_offset + len(interp_bytes)
    dyn_offset = (dyn_offset + 7) & ~7

    strings = b"\x00"
    needed_offsets = []
    for name in needed:
        needed_offsets.append(len(strings))
        strings += name.encode("utf-8") + b"\x00"

    dyn_count = len(needed) + 3
    dyn_size = dyn_count * 16
    strtab_offset = dyn_offset + dyn_size
    file_size = strtab_offset + len(strings)

    base_vaddr = 0x400000
    data = bytearray(file_size)
    data[:4] = b"\x7fELF"
    data[4] = 2
    data[5] = 1
    data[6] = 1

    struct.pack_into(
        "<HHIQQQIHHHHHH",
        data,
        16,
        3,
        183,
        1,
        base_vaddr,
        phoff,
        0,
        0,
        header_size,
        program_header_size,
        program_headers,
        0,
        0,
        0,
    )

    ph_index = 0
    struct.pack_into(
        "<IIQQQQQQ",
        data,
        phoff + program_header_size * ph_index,
        1,
        5,
        0,
        base_vaddr,
        base_vaddr,
        file_size,
        file_size,
        0x1000,
    )
    ph_index += 1

    if interpreter is not None:
        struct.pack_into(
            "<IIQQQQQQ",
            data,
            phoff + program_header_size * ph_index,
            3,
            4,
            interp_offset,
            base_vaddr + interp_offset,
            base_vaddr + interp_offset,
            len(interp_bytes),
            len(interp_bytes),
            1,
        )
        ph_index += 1

    struct.pack_into(
        "<IIQQQQQQ",
        data,
        phoff + program_header_size * ph_index,
        2,
        6,
        dyn_offset,
        base_vaddr + dyn_offset,
        base_vaddr + dyn_offset,
        dyn_size,
        dyn_size,
        8,
    )

    data[interp_offset:interp_offset + len(interp_bytes)] = interp_bytes

    cursor = dyn_offset
    for offset in needed_offsets:
        struct.pack_into("<qQ", data, cursor, 1, offset)
        cursor += 16
    struct.pack_into("<qQ", data, cursor, 5, base_vaddr + strtab_offset)
    cursor += 16
    struct.pack_into("<qQ", data, cursor, 10, len(strings))
    cursor += 16
    struct.pack_into("<qQ", data, cursor, 0, 0)

    data[strtab_offset:strtab_offset + len(strings)] = strings
    path.write_bytes(data)


class Aapt2CandidateTests(unittest.TestCase):
    def test_inspects_android_aarch64_elf(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "aapt2"
            build_minimal_elf64_aarch64(binary)

            evidence = candidate.inspect_elf(binary)

            self.assertEqual(evidence.elf_class, 2)
            self.assertEqual(evidence.machine, 183)
            self.assertEqual(evidence.interpreter, "/system/bin/linker64")
            self.assertEqual(evidence.needed, ("libc.so", "libdl.so"))
            self.assertTrue(evidence.linkage_policy_ok)
            self.assertEqual(evidence.linkage_policy_violations, ())

    def test_rejects_glibc_dependency(self):
        violations = candidate.evaluate_android_linkage(
            "/system/bin/linker64",
            ("libc.so.6", "libdl.so"),
        )
        self.assertTrue(any("glibc" in item for item in violations))

    def test_rejects_desktop_interpreter(self):
        violations = candidate.evaluate_android_linkage(
            "/lib64/ld-linux-x86-64.so.2",
            ("libc.so",),
        )
        self.assertTrue(any("interpreter" in item for item in violations))

    def test_manifest_is_arm64_native_tool(self):
        manifest = candidate.build_candidate_manifest(
            source_repository="aosp/platform/frameworks/base",
            source_revision="test-revision",
            artifact_sha256="a" * 64,
            artifact_size=1234,
            min_api=24,
        )

        self.assertEqual(manifest["packId"], "org.etroute.aapt2")
        self.assertEqual(manifest["abis"], ["arm64-v8a"])
        self.assertEqual(manifest["tools"][0]["toolId"], "aapt2")
        self.assertEqual(manifest["tools"][0]["kind"], "NATIVE_EXECUTABLE")
        self.assertFalse(manifest["dependencies"])

    def test_prepare_candidate_stages_without_granting_trust(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "source-aapt2"
            destination = root / "candidate"
            build_minimal_elf64_aarch64(binary)

            evidence = candidate.prepare_candidate(
                binary,
                destination,
                source_repository="example/aapt2",
                source_revision="test",
                min_api=24,
            )

            self.assertEqual(
                evidence["status"],
                "STRUCTURALLY_VERIFIED_CANDIDATE",
            )
            self.assertFalse(evidence["trustGranted"])
            self.assertFalse(evidence["published"])
            self.assertFalse(evidence["runtimeProbePassed"])
            self.assertTrue((destination / "artifacts" / "aapt2").is_file())
            self.assertTrue((destination / "candidate-manifest.json").is_file())
            self.assertTrue((destination / "candidate-evidence.json").is_file())


if __name__ == "__main__":
    unittest.main()
