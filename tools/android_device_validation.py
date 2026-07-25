#!/usr/bin/env python3
"""Run and record ETroute's one-shot Android device validation sequence.

Stdlib only. This tool does not claim device verification unless the actual
bridge runs succeed and the returned diagnostic evidence satisfies the checks.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

SCHEMA_VERSION = 1


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_json_file(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON root in {path} must be an object")
    return value


def parse_uptime(value: str) -> int:
    parts = value.split(":")
    if len(parts) != 3:
        raise ValueError(f"invalid uptime value: {value!r}")
    hours, minutes, seconds = (int(part) for part in parts)
    if minutes not in range(60) or seconds not in range(60) or hours < 0:
        raise ValueError(f"invalid uptime value: {value!r}")
    return hours * 3600 + minutes * 60 + seconds


def run_bridge(args: argparse.Namespace, *, run_index: int, output_path: Path) -> dict[str, Any]:
    request_id = f"{args.run_id}-request-{run_index}"
    session_id = f"{args.run_id}-session-{run_index}"
    command = [
        args.python,
        str(args.bridge),
        args.environment,
        "--etroute",
        str(args.etroute),
        "--proot",
        args.proot,
        "--timeout",
        str(args.timeout),
        "--request-id",
        request_id,
        "--session-id",
        session_id,
        "--json-out",
        str(output_path),
    ]
    if args.kernel_release:
        command += ["--kernel-release", args.kernel_release]
    if args.guest_python:
        command += ["--guest-python", args.guest_python]
    if args.kill_on_exit:
        command.append("--kill-on-exit")

    started = datetime.now(timezone.utc)
    completed = subprocess.run(
        command,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=args.timeout + 10,
        check=False,
    )
    finished = datetime.now(timezone.utc)
    envelope = parse_json_file(output_path) if output_path.is_file() else None
    return {
        "index": run_index,
        "started_at": started.isoformat(),
        "finished_at": finished.isoformat(),
        "duration_seconds": (finished - started).total_seconds(),
        "command": command,
        "process": {
            "exit_code": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
        },
        "envelope_path": str(output_path),
        "envelope_sha256": sha256_file(output_path) if output_path.is_file() else None,
        "envelope": envelope,
    }


def extract_diagnostic(run: dict[str, Any]) -> dict[str, Any]:
    envelope = run.get("envelope")
    if not isinstance(envelope, dict) or envelope.get("ok") is not True:
        raise ValueError("bridge envelope is missing or unsuccessful")
    diagnostic = envelope.get("diagnostic")
    if not isinstance(diagnostic, dict):
        raise ValueError("successful bridge envelope lacks diagnostic object")
    return diagnostic


def evaluate(runs: list[dict[str, Any]], expected_kernel: str | None) -> dict[str, Any]:
    checks: dict[str, dict[str, Any]] = {}

    def record(name: str, passed: bool, evidence: Any) -> None:
        checks[name] = {"status": "PASS" if passed else "FAIL", "evidence": evidence}

    record(
        "two_bridge_runs_succeeded",
        len(runs) == 2 and all(
            run["process"]["exit_code"] == 0
            and isinstance(run.get("envelope"), dict)
            and run["envelope"].get("ok") is True
            for run in runs
        ),
        [run["process"]["exit_code"] for run in runs],
    )

    try:
        first = extract_diagnostic(runs[0])
        second = extract_diagnostic(runs[1])
    except (IndexError, ValueError) as exc:
        record("diagnostic_contract_available", False, str(exc))
        return {"status": "FAIL", "checks": checks}

    record("diagnostic_contract_available", True, [first.get("schema_version"), second.get("schema_version")])
    first_resources = first.get("resources", {})
    second_resources = second.get("resources", {})
    record(
        "required_binds_visible",
        first_resources.get("required_binds_visible") is True
        and second_resources.get("required_binds_visible") is True,
        {
            "first": first_resources.get("bind_mount_visibility"),
            "second": second_resources.get("bind_mount_visibility"),
        },
    )

    first_device = first.get("device", {})
    second_device = second.get("device", {})
    kernel_pass = first_device.get("kernel_release_matches") is not False and second_device.get("kernel_release_matches") is not False
    if expected_kernel:
        kernel_pass = kernel_pass and first_device.get("kernel_release") == expected_kernel and second_device.get("kernel_release") == expected_kernel
    record(
        "kernel_release_matches",
        kernel_pass,
        {
            "expected": expected_kernel,
            "first": first_device.get("kernel_release"),
            "second": second_device.get("kernel_release"),
        },
    )

    first_activation = first.get("activation", {})
    second_activation = second.get("activation", {})
    first_marker = first_activation.get("marker", {})
    second_marker = second_activation.get("marker", {})
    try:
        first_uptime = parse_uptime(str(first_activation.get("uptime")))
        second_uptime = parse_uptime(str(second_activation.get("uptime")))
        uptime_ok = second_uptime >= first_uptime
    except (TypeError, ValueError) as exc:
        first_uptime = second_uptime = None
        uptime_ok = False
        uptime_error = str(exc)
    else:
        uptime_error = None
    marker_ok = (
        first_marker.get("persistent") is True
        and second_marker.get("persistent") is True
        and first_marker.get("location") == second_marker.get("location")
        and second_activation.get("is_first_activation") is False
        and uptime_ok
    )
    record(
        "activation_marker_persists",
        marker_ok,
        {
            "first_is_first": first_activation.get("is_first_activation"),
            "second_is_first": second_activation.get("is_first_activation"),
            "marker_location": second_marker.get("location"),
            "first_uptime_seconds": first_uptime,
            "second_uptime_seconds": second_uptime,
            "uptime_error": uptime_error,
        },
    )

    integrations = [first.get("integration", {}), second.get("integration", {})]
    record(
        "etumax_correlation_validated",
        all(item.get("declared_caller") == "etumax" for item in integrations)
        and len({item.get("session_id") for item in integrations}) == 2,
        integrations,
    )

    overall = "PASS" if all(item["status"] == "PASS" for item in checks.values()) else "FAIL"
    return {"status": overall, "checks": checks}


def build_parser() -> argparse.ArgumentParser:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("environment")
    parser.add_argument("--bridge", type=Path, default=root / "tools" / "etumax_bridge.py")
    parser.add_argument("--etroute", type=Path, default=root / "etroute.py")
    parser.add_argument("--python", default=sys.executable)
    parser.add_argument("--guest-python", default="/usr/bin/env")
    parser.add_argument("--proot", default="proot")
    parser.add_argument("--kernel-release")
    parser.add_argument("--kill-on-exit", action="store_true")
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--delay", type=float, default=2.0)
    parser.add_argument("--evidence-dir", type=Path, default=root / "evidence" / "android")
    parser.add_argument("--run-id", default=uuid.uuid4().hex)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    args.bridge = args.bridge.expanduser().resolve()
    args.etroute = args.etroute.expanduser().resolve()
    evidence_dir = args.evidence_dir.expanduser().resolve() / args.run_id
    evidence_dir.mkdir(parents=True, exist_ok=False)

    metadata = {
        "schema_version": SCHEMA_VERSION,
        "run_id": args.run_id,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "environment": args.environment,
        "host": {
            "platform": platform.platform(),
            "python": sys.version,
            "executable": sys.executable,
            "cwd": os.getcwd(),
        },
        "inputs": {
            "kernel_release": args.kernel_release,
            "proot": args.proot,
            "guest_python": args.guest_python,
            "delay_seconds": args.delay,
        },
    }
    runs = [run_bridge(args, run_index=1, output_path=evidence_dir / "bridge-run-1.json")]
    time.sleep(max(0.0, args.delay))
    runs.append(run_bridge(args, run_index=2, output_path=evidence_dir / "bridge-run-2.json"))
    evaluation = evaluate(runs, args.kernel_release)
    report = {**metadata, "runs": runs, "evaluation": evaluation}
    report_path = evidence_dir / "ANDROID_DEVICE_VALIDATION.json"
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    checksum_path = evidence_dir / "SHA256SUMS.txt"
    checksum_lines = []
    for path in sorted(evidence_dir.iterdir()):
        if path.is_file() and path.name != checksum_path.name:
            checksum_lines.append(f"{sha256_file(path)}  {path.name}")
    checksum_path.write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")
    print(json.dumps({
        "schema_version": SCHEMA_VERSION,
        "status": evaluation["status"],
        "run_id": args.run_id,
        "evidence_directory": str(evidence_dir),
        "report": str(report_path),
        "checksums": str(checksum_path),
    }, indent=2, sort_keys=True))
    return 0 if evaluation["status"] == "PASS" else 30


if __name__ == "__main__":
    raise SystemExit(main())
