#!/usr/bin/env python3
"""ETumax-to-ETroute one-shot subprocess bridge.

Runs ETroute's diagnostic command, captures stdout, validates its JSON contract,
and emits one machine-readable terminal execution envelope. Stdlib only; no
network use.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any, Sequence

BRIDGE_SCHEMA_VERSION = 2
DIAGNOSTIC_SCHEMA_VERSION = 1
BACKEND_NAME = "etroute"
STATE_COMPLETED = "COMPLETED"
STATE_FAILED = "FAILED"


class ContractError(RuntimeError):
    pass


def parse_single_json_document(text: str) -> dict[str, Any]:
    decoder = json.JSONDecoder()
    stripped = text.lstrip()
    try:
        value, end = decoder.raw_decode(stripped)
    except json.JSONDecodeError as exc:
        raise ContractError(f"ETroute stdout was not valid JSON: {exc}") from exc
    if stripped[end:].strip():
        raise ContractError("ETroute stdout contained trailing non-JSON output")
    if not isinstance(value, dict):
        raise ContractError("diagnostic JSON root must be an object")
    return value


def require_mapping(parent: dict[str, Any], key: str) -> dict[str, Any]:
    value = parent.get(key)
    if not isinstance(value, dict):
        raise ContractError(f"diagnostic field {key!r} must be an object")
    return value


def validate_diagnostic(report: dict[str, Any], *, environment: str, session_id: str) -> None:
    if report.get("schema_version") != DIAGNOSTIC_SCHEMA_VERSION:
        raise ContractError(
            f"unsupported diagnostic schema: {report.get('schema_version')!r}"
        )
    resources = require_mapping(report, "resources")
    device = require_mapping(report, "device")
    activation = require_mapping(report, "activation")
    integration = require_mapping(report, "integration")
    require_mapping(activation, "marker")

    if resources.get("required_binds_visible") is not True:
        raise ContractError("required guest bind locations were not all visible")
    if device.get("kernel_release_matches") is False:
        raise ContractError("guest kernel release did not match the requested value")
    if integration.get("declared_caller") != "etumax":
        raise ContractError("guest did not report ETumax as the declared caller")
    if integration.get("environment_name") != environment:
        raise ContractError("guest environment identity did not match the request")
    if integration.get("session_id") != session_id:
        raise ContractError("guest session correlation identifier did not match")


def build_command(args: argparse.Namespace, session_id: str) -> list[str]:
    command = [
        args.python,
        str(Path(args.etroute).expanduser().resolve()),
        "diagnose",
        args.environment,
        "--caller",
        "etumax",
        "--session-id",
        session_id,
        "--proot",
        args.proot,
        "--strict",
    ]
    if args.kernel_release:
        command += ["--kernel-release", args.kernel_release]
    if args.guest_python:
        command += ["--python", args.guest_python]
    if args.kill_on_exit:
        command.append("--kill-on-exit")
    return command


def _base_envelope(
    *,
    request_id: str,
    session_id: str,
    environment: str,
    started_at_ms: int,
    finished_at_ms: int,
    state: str,
) -> dict[str, Any]:
    return {
        "schema_version": BRIDGE_SCHEMA_VERSION,
        "backend": BACKEND_NAME,
        "state": state,
        "request_id": request_id,
        "session_id": session_id,
        "environment": environment,
        "started_at_ms": started_at_ms,
        "finished_at_ms": finished_at_ms,
        "duration_ms": max(0, finished_at_ms - started_at_ms),
    }


def run_bridge(args: argparse.Namespace) -> tuple[dict[str, Any], int]:
    request_id = args.request_id or uuid.uuid4().hex
    session_id = args.session_id or uuid.uuid4().hex
    command = build_command(args, session_id)
    started_at_ms = int(time.time() * 1000)

    try:
        completed = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=args.timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        finished_at_ms = int(time.time() * 1000)
        envelope = _base_envelope(
            request_id=request_id,
            session_id=session_id,
            environment=args.environment,
            started_at_ms=started_at_ms,
            finished_at_ms=finished_at_ms,
            state=STATE_FAILED,
        )
        envelope.update(
            {
                "ok": False,
                "error": {"kind": "timeout", "message": str(exc)},
            }
        )
        return envelope, 20
    except OSError as exc:
        finished_at_ms = int(time.time() * 1000)
        envelope = _base_envelope(
            request_id=request_id,
            session_id=session_id,
            environment=args.environment,
            started_at_ms=started_at_ms,
            finished_at_ms=finished_at_ms,
            state=STATE_FAILED,
        )
        envelope.update(
            {
                "ok": False,
                "error": {"kind": "launch_error", "message": str(exc)},
            }
        )
        return envelope, 20

    finished_at_ms = int(time.time() * 1000)
    try:
        diagnostic = parse_single_json_document(completed.stdout)
        if completed.returncode != 0:
            raise ContractError(f"ETroute diagnostic exited with code {completed.returncode}")
        validate_diagnostic(diagnostic, environment=args.environment, session_id=session_id)
    except ContractError as exc:
        envelope = _base_envelope(
            request_id=request_id,
            session_id=session_id,
            environment=args.environment,
            started_at_ms=started_at_ms,
            finished_at_ms=finished_at_ms,
            state=STATE_FAILED,
        )
        envelope.update(
            {
                "ok": False,
                "process": {
                    "exit_code": completed.returncode,
                    "stdout": completed.stdout,
                    "stderr": completed.stderr,
                },
                "error": {"kind": "contract_error", "message": str(exc)},
            }
        )
        return envelope, 21

    envelope = _base_envelope(
        request_id=request_id,
        session_id=session_id,
        environment=args.environment,
        started_at_ms=started_at_ms,
        finished_at_ms=finished_at_ms,
        state=STATE_COMPLETED,
    )
    envelope.update(
        {
            "ok": True,
            "process": {
                "exit_code": completed.returncode,
                "stdout": completed.stdout,
                "stderr": completed.stderr,
            },
            "diagnostic": diagnostic,
        }
    )
    return envelope, 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("environment")
    parser.add_argument("--etroute", default=str(Path(__file__).resolve().parents[1] / "etroute.py"))
    parser.add_argument("--python", default=sys.executable, help="host Python used to launch etroute.py")
    parser.add_argument("--guest-python", default="/usr/bin/env")
    parser.add_argument("--proot", default="proot")
    parser.add_argument("--kernel-release")
    parser.add_argument("--kill-on-exit", action="store_true")
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--request-id")
    parser.add_argument("--session-id")
    parser.add_argument("--json-out", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    envelope, exit_code = run_bridge(args)
    rendered = json.dumps(envelope, indent=2, sort_keys=True) + "\n"
    print(rendered, end="")
    if args.json_out:
        destination = args.json_out.expanduser().resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(f".{destination.name}.tmp-{uuid.uuid4().hex}")
        temporary.write_text(rendered, encoding="utf-8")
        temporary.replace(destination)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
