#!/usr/bin/env python3
"""Verify ETroute Android JNI build outputs without requiring a device.

Scans the Android build tree for libetroute_native_supervisor.so for the
required ABIs, validates ELF machine type and JNI exports, and writes a
machine-readable JSON evidence file.

This is a build/link verification step only. It does not mark JNI device
validation green.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

LIB_NAME = "libetroute_native_supervisor.so"
REQUIRED_ABIS = ("arm64-v8a", "x86_64")
EXPECTED_MACHINE = {
    "arm64-v8a": ("AArch64",),
    "x86_64": ("Advanced Micro Devices X86-64", "AMD x86-64", "X86-64"),
}
REQUIRED_SYMBOLS = (
    "Java_org_nemack_universalfilelab_etroute_JniNativeSupervisor_nativeAbiVersion",
    "Java_org_nemack_universalfilelab_etroute_JniNativeSupervisor_nativeRun",
)


@dataclass
class AbiEvidence:
    abi: str
    library: str | None = None
    size_bytes: int | None = None
    machine: str | None = None
    machine_ok: bool = False
    required_symbols: dict[str, bool] | None = None
    symbols_ok: bool = False
    verified: bool = False
    error: str | None = None


def run_text(command: list[str]) -> str:
    completed = subprocess.run(
        command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"command failed ({completed.returncode}): {' '.join(command)}\n"
            f"{completed.stdout}"
        )
    return completed.stdout


def find_tool(candidates: Iterable[str]) -> str | None:
    for candidate in candidates:
        resolved = shutil.which(candidate)
        if resolved:
            return resolved
    return None


def find_libraries(build_root: Path, abi: str) -> list[Path]:
    matches: list[Path] = []
    for path in build_root.rglob(LIB_NAME):
        if abi in path.parts or abi in str(path):
            matches.append(path)
    return sorted(matches, key=lambda p: ("stripped" not in str(p), len(str(p))))


def read_machine(readelf: str, library: Path) -> str:
    output = run_text([readelf, "-h", str(library)])
    for line in output.splitlines():
        if "Machine:" in line:
            return line.split("Machine:", 1)[1].strip()
    raise RuntimeError("ELF header did not contain Machine field")


def read_dynamic_symbols(nm: str, library: Path) -> str:
    return run_text([nm, "-D", "--defined-only", str(library)])


def verify_abi(
    build_root: Path,
    abi: str,
    readelf: str,
    nm: str,
) -> AbiEvidence:
    evidence = AbiEvidence(abi=abi)
    try:
        matches = find_libraries(build_root, abi)
        if not matches:
            raise RuntimeError(f"{LIB_NAME} not found for {abi}")

        library = matches[0]
        evidence.library = str(library.resolve())
        evidence.size_bytes = library.stat().st_size
        if evidence.size_bytes <= 0:
            raise RuntimeError("shared library is empty")

        evidence.machine = read_machine(readelf, library)
        expected = EXPECTED_MACHINE[abi]
        evidence.machine_ok = any(token in evidence.machine for token in expected)
        if not evidence.machine_ok:
            raise RuntimeError(
                f"unexpected ELF machine for {abi}: {evidence.machine!r}; expected {expected}"
            )

        symbol_text = read_dynamic_symbols(nm, library)
        evidence.required_symbols = {
            symbol: symbol in symbol_text for symbol in REQUIRED_SYMBOLS
        }
        evidence.symbols_ok = all(evidence.required_symbols.values())
        if not evidence.symbols_ok:
            missing = [
                name for name, present in evidence.required_symbols.items() if not present
            ]
            raise RuntimeError(f"missing JNI exports: {', '.join(missing)}")

        evidence.verified = True
    except Exception as exc:  # evidence boundary: fail visibly and continue other ABI
        evidence.error = str(exc)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--build-root",
        default="android/build",
        help="Android Gradle build directory to inspect",
    )
    parser.add_argument(
        "--output",
        default="evidence/android_jni/latest/build-verification.json",
        help="JSON evidence destination",
    )
    args = parser.parse_args()

    build_root = Path(args.build_root)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)

    readelf = find_tool(("llvm-readelf", "readelf"))
    nm = find_tool(("llvm-nm", "nm"))

    payload: dict[str, object] = {
        "schema_version": 1,
        "kind": "etroute-android-jni-build-verification",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "library": LIB_NAME,
        "required_abis": list(REQUIRED_ABIS),
        "required_symbols": list(REQUIRED_SYMBOLS),
        "build_root": str(build_root.resolve()),
        "tools": {
            "readelf": readelf,
            "nm": nm,
        },
        "status": "FAILED",
        "abis": [],
    }

    if readelf is None or nm is None:
        payload["error"] = "readelf/llvm-readelf and nm/llvm-nm are required"
        output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(payload, indent=2))
        return 2

    evidence = [verify_abi(build_root, abi, readelf, nm) for abi in REQUIRED_ABIS]
    payload["abis"] = [asdict(item) for item in evidence]

    if all(item.verified for item in evidence):
        payload["status"] = "JNI_NDK_BUILD_VERIFIED"
        exit_code = 0
    else:
        payload["status"] = "FAILED"
        exit_code = 1

    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, indent=2))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
