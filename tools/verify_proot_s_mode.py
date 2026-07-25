#!/usr/bin/env python3
"""
Verify the bind set produced by `proot -S ROOTFS` against ETroute's
explicit package-mode bind policy.

Run this in development or CI when the pinned PRoot build changes. It is
not intended to run during every ETroute installation.

Exit codes:
    0 match
    2 local/CLI validation error
    3 PRoot identity pin mismatch
    4 PRoot execution failure
    5 diagnostics could not be parsed safely
    6 bind-policy drift detected
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

S_MODE_RELATIVE_PATHS: tuple[str, ...] = (
    "/etc/host.conf",
    "/etc/hosts",
    "/etc/nsswitch.conf",
    "/etc/resolv.conf",
    "/dev",
    "/sys",
    "/proc",
    "/tmp",
    "/run/shm",
    "$HOME",
)

BIND_LINE_RE = re.compile(
    r"^\s*proot\s+info:\s+binding\s*=\s*(?P<binding>.+?)\s*$",
    re.IGNORECASE,
)
VERSION_PATTERNS = (
    re.compile(r"\bproot(?:\s+version)?\s+([0-9][^\s,;]*)", re.IGNORECASE),
    re.compile(r"\b([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9]+)?)\b"),
)


@dataclass(frozen=True)
class BindRecord:
    raw: str
    host: str
    guest: str | None


@dataclass(frozen=True)
class AuditReport:
    schema_version: int
    captured_at: str
    proot_binary: str
    proot_binary_sha256: str
    proot_version_output: str
    detected_version: str | None
    version_pin_regex: str | None
    expected_binary_sha256: str | None
    binary_sha256_matches: bool | None
    rootfs: str
    command: list[str]
    diagnostic_verbosity: int
    documented_policy: list[str]
    expected_existing_host_paths: list[str]
    observed_host_paths: list[str]
    observed_bind_records: list[dict[str, str | None]]
    ignored_rootfs_bind_records: list[dict[str, str | None]]
    missing_paths: list[str]
    unexpected_paths: list[str]
    matches: bool


class VerificationError(RuntimeError):
    def __init__(self, message: str, exit_code: int) -> None:
        super().__init__(message)
        self.exit_code = exit_code


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_binary(binary: str) -> Path:
    resolved = shutil.which(binary)
    if resolved is None:
        raise VerificationError(f"PRoot executable not found: {binary}", 2)
    path = Path(resolved).resolve()
    if not path.is_file():
        raise VerificationError(f"Resolved PRoot path is not a file: {path}", 2)
    return path


def run_capture(argv: Sequence[str], timeout_seconds: int) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            list(argv),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_seconds,
            check=False,
            env=os.environ.copy(),
        )
    except subprocess.TimeoutExpired as exc:
        raise VerificationError(
            f"Command timed out after {timeout_seconds} seconds: {argv!r}", 4
        ) from exc
    except OSError as exc:
        raise VerificationError(f"Could not execute {argv[0]}: {exc}", 4) from exc


def get_version_output(binary: Path, timeout_seconds: int) -> str:
    attempts = (
        [str(binary), "--version"],
        [str(binary), "-V"],
        [str(binary), "--help"],
    )
    captured: list[str] = []
    for argv in attempts:
        result = run_capture(argv, timeout_seconds)
        text = "\n".join(part for part in (result.stdout, result.stderr) if part).strip()
        if text:
            captured.append(f"$ {' '.join(argv)}\n{text}")
        if result.returncode == 0 and text:
            return text
    if captured:
        return "\n\n".join(captured)
    raise VerificationError("PRoot produced no version or help output.", 5)


def detect_version(text: str) -> str | None:
    for pattern in VERSION_PATTERNS:
        match = pattern.search(text)
        if match:
            return match.group(1)
    return None


def normalize_path(value: str) -> str:
    expanded = os.path.expandvars(os.path.expanduser(value))
    return os.path.normpath(os.path.abspath(expanded))


def expected_existing_paths() -> set[str]:
    if not os.environ.get("HOME"):
        raise VerificationError(
            "HOME is not set. Refusing to shrink the expected -S bind set "
            "silently; run the audit with an explicit HOME environment.",
            2,
        )

    expected: set[str] = set()
    for value in S_MODE_RELATIVE_PATHS:
        normalized = normalize_path(value)
        if Path(normalized).exists():
            expected.add(normalized)
    return expected


def split_binding(raw: str) -> BindRecord:
    value = raw.strip()
    if ":" in value:
        host, guest = value.split(":", 1)
        return BindRecord(value, normalize_path(host), guest.strip() or None)
    return BindRecord(value, normalize_path(value), None)


def parse_bind_records(diagnostics: str) -> list[BindRecord]:
    records: list[BindRecord] = []
    for line in diagnostics.splitlines():
        match = BIND_LINE_RE.match(line)
        if match:
            records.append(split_binding(match.group("binding")))
    return records


def is_rootfs_record(record: BindRecord, rootfs: Path) -> bool:
    root = normalize_path(str(rootfs))
    return record.host == root and record.guest in (None, "", "/")


def choose_guest_command(rootfs: Path, explicit: Sequence[str]) -> list[str]:
    if explicit:
        return list(explicit)
    for candidate in ("/bin/true", "/usr/bin/true", "/bin/sh", "/usr/bin/env"):
        if (rootfs / candidate.lstrip("/")).exists():
            return [candidate, "-c", "exit 0"] if candidate == "/bin/sh" else [candidate]
    raise VerificationError(
        "No usable guest command found. Supply one after '--', for example "
        "'-- /bin/sh -c true'.",
        2,
    )


def write_json_atomic(path: Path, report: AuditReport) -> None:
    path = path.expanduser().resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    temporary.write_text(
        json.dumps(asdict(report), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


def audit(args: argparse.Namespace) -> AuditReport:
    binary = resolve_binary(args.proot)
    rootfs = Path(args.rootfs).expanduser().resolve()
    if not rootfs.is_dir():
        raise VerificationError(f"Rootfs directory does not exist: {rootfs}", 2)

    binary_sha256 = sha256_file(binary)

    if args.expected_sha256:
        expected_sha256 = args.expected_sha256.strip().lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
            raise VerificationError(
                "--expected-sha256 must be exactly 64 hexadecimal characters.",
                2,
            )
        if binary_sha256 != expected_sha256:
            raise VerificationError(
                "PRoot binary SHA-256 pin failed.\n"
                f"Expected: {expected_sha256}\n"
                f"Actual:   {binary_sha256}",
                3,
            )

    version_output = get_version_output(binary, args.timeout)
    detected_version = detect_version(version_output)

    if args.expected_version_regex:
        try:
            version_re = re.compile(args.expected_version_regex)
        except re.error as exc:
            raise VerificationError(f"Invalid version regex: {exc}", 2) from exc
        subject = detected_version or version_output
        if version_re.search(subject) is None:
            raise VerificationError(
                "PRoot version pin failed.\n"
                f"Expected regex: {args.expected_version_regex}\n"
                f"Detected version: {detected_version!r}",
                3,
            )

    guest_command = choose_guest_command(rootfs, args.command)
    invocation = [
        str(binary),
        f"-v{args.verbosity}",
        "-S",
        str(rootfs),
        "--",
        *guest_command,
    ]
    result = run_capture(invocation, args.timeout)
    diagnostics = "\n".join(part for part in (result.stdout, result.stderr) if part)

    if result.returncode != 0:
        raise VerificationError(
            f"PRoot -S probe failed with exit code {result.returncode}.\n"
            f"Command: {invocation!r}\nDiagnostics:\n{diagnostics}",
            4,
        )

    records = parse_bind_records(diagnostics)
    if not records:
        raise VerificationError(
            "No 'proot info: binding = ...' records were found. The selected "
            "PRoot build may use a different diagnostic format or the requested "
            "verbosity may be insufficient. Treat this as a failed audit.",
            5,
        )

    rootfs_records = [record for record in records if is_rootfs_record(record, rootfs)]
    auxiliary = [record for record in records if not is_rootfs_record(record, rootfs)]
    observed = {record.host for record in auxiliary}
    expected = expected_existing_paths()
    missing = sorted(expected - observed)
    unexpected = sorted(observed - expected)

    return AuditReport(
        schema_version=1,
        captured_at=datetime.now(timezone.utc).isoformat(),
        proot_binary=str(binary),
        proot_binary_sha256=binary_sha256,
        proot_version_output=version_output,
        detected_version=detected_version,
        version_pin_regex=args.expected_version_regex,
        expected_binary_sha256=(
            args.expected_sha256.strip().lower() if args.expected_sha256 else None
        ),
        binary_sha256_matches=True if args.expected_sha256 else None,
        rootfs=str(rootfs),
        command=invocation,
        diagnostic_verbosity=args.verbosity,
        documented_policy=list(S_MODE_RELATIVE_PATHS),
        expected_existing_host_paths=sorted(expected),
        observed_host_paths=sorted(observed),
        observed_bind_records=[asdict(record) for record in auxiliary],
        ignored_rootfs_bind_records=[asdict(record) for record in rootfs_records],
        missing_paths=missing,
        unexpected_paths=unexpected,
        matches=not missing and not unexpected,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Compare the bind set produced by PRoot -S with ETroute's explicit "
            "package-mode bind policy."
        )
    )
    parser.add_argument("--rootfs", required=True, help="Existing rootfs for the probe.")
    parser.add_argument("--proot", default="proot", help="PRoot executable or path.")
    parser.add_argument(
        "--expected-sha256",
        help=(
            "Optional exact SHA-256 pin for the PRoot executable. This is the "
            "primary CI identity check and is evaluated before the version regex."
        ),
    )
    parser.add_argument(
        "--expected-version-regex",
        help=(
            "Optional human-readable PRoot version constraint. Use SHA-256 as "
            "the primary identity pin in CI."
        ),
    )
    parser.add_argument(
        "--verbosity", type=int, default=2, choices=range(1, 10), metavar="1-9"
    )
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command and args.command[0] == "--":
        args.command = args.command[1:]

    try:
        report = audit(args)
    except VerificationError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return exc.exit_code

    if args.json_out:
        write_json_atomic(args.json_out, report)

    print(f"PRoot binary:  {report.proot_binary}")
    print(f"PRoot SHA-256: {report.proot_binary_sha256}")
    if report.expected_binary_sha256:
        print("SHA-256 pin:   MATCH")
    else:
        print("SHA-256 pin:   not requested")
    print(f"Version:       {report.detected_version or '(not parsed)'}")
    print(f"Rootfs:        {report.rootfs}\n")

    print("Expected existing host paths:")
    for path in report.expected_existing_host_paths:
        print(f"  = {path}")
    print("Observed auxiliary bind paths:")
    for path in report.observed_host_paths:
        marker = "=" if path in report.expected_existing_host_paths else "+"
        print(f"  {marker} {path}")

    if report.missing_paths:
        print("Missing expected paths:")
        for path in report.missing_paths:
            print(f"  - {path}")
    if report.unexpected_paths:
        print("Unexpected paths:")
        for path in report.unexpected_paths:
            print(f"  + {path}")

    if report.matches:
        identity_note = (
            "exact binary SHA-256 pin matched"
            if report.expected_binary_sha256
            else "binary SHA-256 recorded but not pinned"
        )
        print(
            "\nPASS: observed -S binds match ETroute's explicit policy; "
            f"{identity_note}. Version text is informational."
        )
        return 0

    print(
        "\nFAIL: observed -S binds differ from ETroute's explicit policy. "
        "Review the PRoot change before updating ETroute.",
        file=sys.stderr,
    )
    return 6


if __name__ == "__main__":
    raise SystemExit(main())
