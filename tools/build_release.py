#!/usr/bin/env python3
"""Build and locally validate a reproducible ETroute release archive."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import py_compile
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Sequence

FIXED_ZIP_TIME = (2026, 1, 1, 0, 0, 0)
EXCLUDED_PARTS = {"__pycache__", ".git", ".pytest_cache", ".mypy_cache"}
EXCLUDED_SUFFIXES = {".pyc", ".pyo"}
REQUIRED_MODULES = (
    "etroute.py",
    "tools/first_activation_diagnostic.py",
    "tools/etumax_bridge.py",
    "tools/build_release.py",
    "tools/android_device_validation.py",
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def included_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if any(part in EXCLUDED_PARTS for part in relative.parts):
            continue
        if path.suffix in EXCLUDED_SUFFIXES:
            continue
        if relative.parts and relative.parts[0] == "release":
            continue
        files.append(path)
    return sorted(files, key=lambda item: item.relative_to(root).as_posix())


def tree_digest(root: Path, files: Iterable[Path]) -> str:
    digest = hashlib.sha256()
    for path in files:
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        data = path.read_bytes()
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    return digest.hexdigest()


def compile_modules(root: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="etroute-pyc-") as tmp:
        for relative in REQUIRED_MODULES:
            source = root / relative
            if not source.is_file():
                raise RuntimeError(f"required module missing: {relative}")
            destination = Path(tmp) / (relative.replace("/", "_") + "c")
            py_compile.compile(str(source), cfile=str(destination), doraise=True)


def run_tests(root: Path) -> tuple[int, str]:
    env = os.environ.copy()
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    completed = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        cwd=root,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError("unit tests failed:\n" + completed.stdout)
    marker = "Ran "
    count = 0
    for line in completed.stdout.splitlines():
        if line.startswith(marker) and " test" in line:
            try:
                count = int(line.split()[1])
            except (IndexError, ValueError):
                pass
    return count, completed.stdout


def build_zip(root: Path, destination: Path, files: Sequence[Path]) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.tmp-{os.getpid()}")
    prefix = root.name
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            relative = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo(f"{prefix}/{relative}", date_time=FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if os.access(path, os.X_OK) else 0o644) << 16
            archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    os.replace(temporary, destination)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--release-manifest", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    root = args.root.expanduser().resolve()
    output = args.output.expanduser().resolve()
    status_path = root / "ETROUTE_STATUS.json"
    if not status_path.is_file():
        raise SystemExit("ETROUTE_STATUS.json is required")
    status = json.loads(status_path.read_text(encoding="utf-8"))
    compile_modules(root)
    test_count, _ = run_tests(root)
    minimum = int(status["local_validation"]["unit_tests"]["minimum_expected_tests"])
    if test_count < minimum:
        raise SystemExit(f"expected at least {minimum} tests, observed {test_count}")
    files = included_files(root)
    source_digest = tree_digest(root, files)
    build_zip(root, output, files)
    artifact_digest = sha256_file(output)
    manifest = {
        "schema_version": 1,
        "project": "ETroute",
        "release": status.get("release"),
        "created_at": datetime.now(timezone.utc).isoformat(),
        "artifact": {
            "filename": output.name,
            "sha256": artifact_digest,
            "size_bytes": output.stat().st_size,
            "status": "VERIFIED_LOCAL"
        },
        "source_tree": {
            "directory_name": root.name,
            "sha256": source_digest,
            "included_file_count": len(files),
            "status": "VERIFIED_LOCAL"
        },
        "validation": {
            "compiled_modules": list(REQUIRED_MODULES),
            "unit_tests_passed": test_count,
            "android_status": status["android_validation"]["status"]
        }
    }
    manifest_path = (args.release_manifest or output.with_suffix(".release.json")).expanduser().resolve()
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
