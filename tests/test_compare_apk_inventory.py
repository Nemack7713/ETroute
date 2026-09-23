#!/usr/bin/env python3
from __future__ import annotations

import copy
import unittest

from tools import compare_apk_inventory as comparator


def inventory_record() -> dict:
    return {
        "file": "app.apk",
        "path": "/tmp/app.apk",
        "sha256": "a" * 64,
        "size_bytes": 1000,
        "aapt2_badging": {
            "package": "com.example.app",
            "version_code": 1,
            "version_name": "1.0",
            "compile_sdk_version": "34",
            "min_sdk": "24",
            "target_sdk": "34",
            "max_sdk": None,
            "debuggable": False,
            "launchable_activity": "com.example.app.MainActivity",
            "permissions": ["android.permission.INTERNET"],
            "native_code": ["arm64-v8a"],
        },
        "structure": {
            "valid_zip": True,
            "entry_count": 4,
            "compressed_bytes": 700,
            "uncompressed_bytes": 1000,
            "unsafe_paths": [],
            "duplicate_entries": [],
            "manifest": {
                "sha256": "b" * 64,
                "size_bytes": 100,
                "compression": 8,
            },
            "resources_arsc": {
                "sha256": "c" * 64,
                "size_bytes": 200,
                "compression": 0,
                "data_offset": 4096,
                "aligned_4k": True,
                "aligned_16k": False,
            },
            "dex": [
                {
                    "name": "classes.dex",
                    "sha256": "d" * 64,
                    "size_bytes": 300,
                    "compression": 8,
                }
            ],
            "native_libraries": [
                {
                    "path": "lib/arm64-v8a/libx.so",
                    "abi": "arm64-v8a",
                    "name": "libx.so",
                    "sha256": "e" * 64,
                    "size_bytes": 400,
                    "compression": 0,
                    "data_offset": 16384,
                    "aligned_4k": True,
                    "aligned_16k": True,
                    "elf": {
                        "valid": True,
                        "class": 64,
                        "endianness": "little",
                        "type": 3,
                        "machine": 183,
                        "machine_name": "AARCH64",
                        "interpreter": "/system/bin/linker64",
                        "dt_needed": ["libc.so"],
                        "rpath": None,
                        "runpath": None,
                        "pie": True,
                        "gnu_relro": True,
                        "bind_now": True,
                        "nx_stack": True,
                    },
                }
            ],
            "v1_signature_entries": ["META-INF/MANIFEST.MF", "META-INF/CERT.SF"],
            "signing_block": {"present": True, "size_bytes": 4096},
        },
    }


class ApkInventoryComparatorTests(unittest.TestCase):

    def test_identical_inventory_has_no_alerts(self):
        before = inventory_record()
        after = copy.deepcopy(before)

        result = comparator.compare_apk_records(before, after)

        self.assertFalse(result["summary"]["changed"])
        self.assertEqual(result["summary"]["changedSections"], [])
        self.assertEqual(result["alerts"], [])
        self.assertFalse(result["summary"]["trustDecisionMade"])

    def test_package_and_native_hardening_regressions_are_alerted(self):
        before = inventory_record()
        after = copy.deepcopy(before)
        after["sha256"] = "f" * 64
        after["aapt2_badging"]["package"] = "com.example.changed"
        elf = after["structure"]["native_libraries"][0]["elf"]
        elf["pie"] = False
        elf["gnu_relro"] = False
        elf["bind_now"] = False
        elf["nx_stack"] = False

        result = comparator.compare_apk_records(before, after)
        codes = [alert["code"] for alert in result["alerts"]]

        self.assertTrue(result["summary"]["changed"])
        self.assertIn("PACKAGE_ID_CHANGED", codes)
        self.assertIn("NATIVE_PIE_LOST", codes)
        self.assertIn("NATIVE_RELRO_LOST", codes)
        self.assertIn("NATIVE_BIND_NOW_LOST", codes)
        self.assertIn("NATIVE_NX_STACK_LOST", codes)

    def test_unsafe_and_duplicate_entries_are_alerted(self):
        before = inventory_record()
        after = copy.deepcopy(before)
        after["structure"]["unsafe_paths"] = ["../escape"]
        after["structure"]["duplicate_entries"] = ["classes.dex"]

        result = comparator.compare_apk_records(before, after)
        alerts = {(a["code"], a.get("path")) for a in result["alerts"]}

        self.assertIn(("UNSAFE_ZIP_PATH_INTRODUCED", "../escape"), alerts)
        self.assertIn(("DUPLICATE_ZIP_ENTRY_INTRODUCED", "classes.dex"), alerts)

    def test_dex_native_resources_and_signing_changes_are_separated(self):
        before = inventory_record()
        after = copy.deepcopy(before)
        after["structure"]["dex"][0]["sha256"] = "1" * 64
        after["structure"]["native_libraries"][0]["elf"]["dt_needed"] = [
            "libc.so",
            "libm.so",
        ]
        after["structure"]["resources_arsc"]["aligned_16k"] = True
        after["structure"]["signing_block"] = {
            "present": False,
            "size_bytes": None,
        }

        result = comparator.compare_apk_records(before, after)

        self.assertEqual(
            result["dex"]["changed"][0]["name"],
            "classes.dex",
        )
        self.assertIn(
            "dt_needed",
            result["nativeLibraries"]["changed"][0]["elf"],
        )
        self.assertIn(
            "aligned_16k",
            result["resourcesArsc"]["fields"],
        )
        self.assertTrue(
            result["signing"]["apkSigningBlockPresent"]["changed"]
        )

    def test_multi_apk_inventory_requires_selector(self):
        document = {
            "apks": [
                {"file": "one.apk"},
                {"file": "two.apk"},
            ]
        }

        with self.assertRaises(comparator.ComparisonError):
            comparator.select_apk_record(
                document,
                file_name=None,
                label="before",
            )

        selected = comparator.select_apk_record(
            document,
            file_name="two.apk",
            label="before",
        )
        self.assertEqual(selected["file"], "two.apk")


if __name__ == "__main__":
    unittest.main()
