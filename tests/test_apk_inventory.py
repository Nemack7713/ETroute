#!/usr/bin/env python3
from __future__ import annotations

import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools import apk_inventory


def minimal_elf64_aarch64_pie() -> bytes:
    data = bytearray(64)
    data[0:4] = b"\x7fELF"
    data[4] = 2
    data[5] = 1
    data[6] = 1
    struct.pack_into("<H", data, 16, 3)
    struct.pack_into("<H", data, 18, 183)
    struct.pack_into("<Q", data, 32, 0)
    struct.pack_into("<H", data, 54, 56)
    struct.pack_into("<H", data, 56, 0)
    return bytes(data)


def write_apk(path: Path, *, unsafe: bool = False, duplicate: bool = False) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("AndroidManifest.xml", b"binary-manifest")
        archive.writestr("classes.dex", b"dex\n035\x00fixture")
        archive.writestr("resources.arsc", b"resource-table")
        archive.writestr("lib/arm64-v8a/libfixture.so", minimal_elf64_aarch64_pie())
        archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        archive.writestr("META-INF/CERT.SF", b"Signature-Version: 1.0\n")
        if unsafe:
            archive.writestr("../escape.txt", b"escape")
        if duplicate:
            archive.writestr("classes.dex", b"second")


class ApkInventoryTests(unittest.TestCase):

    def test_parse_badging_preserves_uploaded_scanner_fields(self):
        parsed = apk_inventory.parse_badging(
            "package: name='com.example.app' versionCode='42' "
            "versionName='2.1.0' compileSdkVersion='34'\n"
            "sdkVersion:'24'\n"
            "targetSdkVersion:'34'\n"
            "uses-permission: name='android.permission.INTERNET'\n"
            "application-debuggable\n"
            "native-code: 'arm64-v8a' 'armeabi-v7a'\n"
        )

        self.assertEqual(parsed["package"], "com.example.app")
        self.assertEqual(parsed["version_code"], 42)
        self.assertEqual(parsed["version_name"], "2.1.0")
        self.assertEqual(parsed["compile_sdk_version"], "34")
        self.assertEqual(parsed["min_sdk"], "24")
        self.assertEqual(parsed["target_sdk"], "34")
        self.assertTrue(parsed["debuggable"])
        self.assertEqual(
            parsed["permissions"],
            ["android.permission.INTERNET"],
        )
        self.assertEqual(
            parsed["native_code"],
            ["arm64-v8a", "armeabi-v7a"],
        )

    def test_structural_inventory_does_not_require_aapt2(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            write_apk(apk)

            result = apk_inventory.scan_apk(apk)

            self.assertTrue(result["ok"])
            self.assertEqual(len(result["sha256"]), 64)
            structure = result["structure"]
            self.assertTrue(structure["valid_zip"])
            self.assertIsNotNone(structure["manifest"])
            self.assertIsNotNone(structure["resources_arsc"])
            self.assertEqual(
                [item["name"] for item in structure["dex"]],
                ["classes.dex"],
            )
            self.assertEqual(len(structure["native_libraries"]), 1)
            native = structure["native_libraries"][0]
            self.assertEqual(native["abi"], "arm64-v8a")
            self.assertEqual(native["elf"]["class"], 64)
            self.assertEqual(native["elf"]["machine"], 183)
            self.assertEqual(native["elf"]["machine_name"], "AARCH64")
            self.assertEqual(
                structure["v1_signature_entries"],
                ["META-INF/CERT.SF", "META-INF/MANIFEST.MF"],
            )

    def test_unsafe_zip_path_fails_structural_acceptance(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "unsafe.apk"
            write_apk(apk, unsafe=True)

            result = apk_inventory.scan_apk(apk)

            self.assertFalse(result["ok"])
            self.assertIn("unsafe ZIP paths", result["structural_warnings"])
            self.assertEqual(
                result["structure"]["unsafe_paths"],
                ["../escape.txt"],
            )

    def test_duplicate_entry_fails_structural_acceptance(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "duplicate.apk"
            write_apk(apk, duplicate=True)

            result = apk_inventory.scan_apk(apk)

            self.assertFalse(result["ok"])
            self.assertIn(
                "duplicate ZIP entries",
                result["structural_warnings"],
            )
            self.assertEqual(
                result["structure"]["duplicate_entries"],
                ["classes.dex"],
            )

    def test_missing_manifest_fails_structural_acceptance(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "no-manifest.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"dex")

            result = apk_inventory.scan_apk(apk)

            self.assertFalse(result["ok"])
            self.assertIn(
                "AndroidManifest.xml missing",
                result["structural_warnings"],
            )

    def test_aapt2_is_optional_but_explicit_path_must_exist(self):
        self.assertIsNone(apk_inventory.resolve_aapt2(None))
        with self.assertRaises(apk_inventory.InventoryError):
            apk_inventory.resolve_aapt2("/definitely/not/aapt2")


if __name__ == "__main__":
    unittest.main()
