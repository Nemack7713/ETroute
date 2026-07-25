#!/usr/bin/env python3
"""Emit one JSON diagnostic object from inside an ETroute guest."""
from __future__ import annotations

import argparse
import json
import os
import platform
import sys
from pathlib import Path

REQUIRED_BINDS = ("/dev", "/proc", "/sys", "/tmp")
OPTIONAL_BINDS = ("/run/shm",)
EXIT_BIND = 10
EXIT_KERNEL = 11
EXIT_MARKER = 12


def visible(path: str) -> bool:
    target = Path(path)
    return target.exists() and os.access(target, os.R_OK)


def marker_state(path: Path) -> tuple[str, bool]:
    existed = path.exists()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("activated\n", encoding="utf-8")
    return ("persistent" if existed else "first_activation", path.exists())


def build_report() -> tuple[dict, int]:
    expected_kernel = os.environ.get("ETROUTE_EXPECTED_KERNEL_RELEASE")
    actual_kernel = platform.release()
    marker = Path(os.environ.get("ETROUTE_ACTIVATION_MARKER", "/root/.etroute_activation_marker"))
    required = {path: visible(path) for path in REQUIRED_BINDS}
    optional = {path: visible(path) for path in OPTIONAL_BINDS}
    try:
        activation_state, marker_ok = marker_state(marker)
    except OSError:
        activation_state, marker_ok = "error", False

    report = {
        "schema_version": 1,
        "caller": os.environ.get("ETROUTE_CALLER", "unknown"),
        "environment": os.environ.get("ETROUTE_ENVIRONMENT_NAME", "unknown"),
        "session_id": os.environ.get("ETROUTE_SESSION_ID", ""),
        "kernel": {
            "actual": actual_kernel,
            "expected": expected_kernel,
            "matches": expected_kernel is None or actual_kernel == expected_kernel,
        },
        "binds": {"required": required, "optional": optional},
        "activation": {
            "state": activation_state,
            "marker": str(marker),
            "persisted": marker_ok,
        },
        "python": sys.version.split()[0],
    }

    if not all(required.values()):
        return report, EXIT_BIND
    if not report["kernel"]["matches"]:
        return report, EXIT_KERNEL
    if not marker_ok:
        return report, EXIT_MARKER
    return report, 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()
    report, code = build_report()
    print(json.dumps(report, sort_keys=True, separators=(",", ":")))
    return code if args.strict else 0


if __name__ == "__main__":
    raise SystemExit(main())
