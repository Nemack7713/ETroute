#!/usr/bin/env python3
"""Fetch the pinned rendiix Android 12+ arm64 AAPT2 candidate.

This helper downloads exactly one immutable Git object, verifies its byte
length and Git blob SHA-1, then hands it to prepare_aapt2_candidate.py for
ELF/linkage inspection and candidate evidence generation.

It does not grant trust or publish a RuntimePack generation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
import urllib.request
from pathlib import Path
from typing import Any, Sequence

from tools import prepare_aapt2_candidate

SOURCE_REPOSITORY = "rendiix/termux-aapt"
SOURCE_COMMIT = "8d981d41c1228e0ea2733e6d4bfc039d182d1b0f"
SOURCE_PATH = "prebuilt-binary-android-12+/arm64/aapt2"
SOURCE_BLOB_SHA1 = "317ef4b90fef2e5921901447a8463c268fb19bd5"
SOURCE_SIZE_BYTES = 3_498_816
SOURCE_URL = (
    "https://raw.githubusercontent.com/"
    f"{SOURCE_REPOSITORY}/{SOURCE_COMMIT}/"
    "prebuilt-binary-android-12%2B/arm64/aapt2"
)
USER_AGENT = "ETroute-AAPT2-Candidate/1"
MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024


class AcquisitionError(RuntimeError):
    pass


def git_blob_sha1(payload: bytes) -> str:
    header = f"blob {len(payload)}\0".encode("ascii")
    return hashlib.sha1(header + payload).hexdigest()


def download_pinned_binary(
    destination: Path,
    *,
    timeout: float = 60.0,
) -> dict[str, Any]:
    destination = destination.expanduser().resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)

    request = urllib.request.Request(
        SOURCE_URL,
        headers={"User-Agent": USER_AGENT},
    )

    with tempfile.NamedTemporaryFile(
        prefix=f".{destination.name}.",
        suffix=".tmp",
        dir=destination.parent,
        delete=False,
    ) as temporary:
        temporary_path = Path(temporary.name)
        payload = bytearray()
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                while True:
                    chunk = response.read(128 * 1024)
                    if not chunk:
                        break
                    payload.extend(chunk)
                    if len(payload) > MAX_DOWNLOAD_BYTES:
                        raise AcquisitionError(
                            "AAPT2 download exceeded maximum allowed size"
                        )

            if len(payload) != SOURCE_SIZE_BYTES:
                raise AcquisitionError(
                    "AAPT2 size mismatch: "
                    f"expected={SOURCE_SIZE_BYTES} actual={len(payload)}"
                )

            actual_blob_sha1 = git_blob_sha1(bytes(payload))
            if actual_blob_sha1 != SOURCE_BLOB_SHA1:
                raise AcquisitionError(
                    "AAPT2 Git blob mismatch: "
                    f"expected={SOURCE_BLOB_SHA1} actual={actual_blob_sha1}"
                )

            temporary.write(payload)
            temporary.flush()
            os.fsync(temporary.fileno())
        except BaseException:
            temporary_path.unlink(missing_ok=True)
            raise

    os.replace(temporary_path, destination)

    return {
        "sourceRepository": SOURCE_REPOSITORY,
        "sourceCommit": SOURCE_COMMIT,
        "sourcePath": SOURCE_PATH,
        "sourceBlobSha1": SOURCE_BLOB_SHA1,
        "sourceSizeBytes": SOURCE_SIZE_BYTES,
        "sourceUrl": SOURCE_URL,
        "downloadedSha256": hashlib.sha256(payload).hexdigest(),
        "downloadedPath": str(destination),
    }


def _write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(value, indent=2, sort_keys=True) + "\n"
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(rendered, encoding="utf-8")
    os.replace(temporary, path)


def acquire_candidate(
    destination: Path,
    *,
    min_api: int = 31,
) -> dict[str, Any]:
    destination = destination.expanduser().resolve()
    if destination.exists():
        raise AcquisitionError(
            f"destination already exists; refusing mutable replacement: {destination}"
        )

    staging_parent = destination.parent
    staging_parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(
        prefix="etroute-aapt2-acquire-",
        dir=staging_parent,
    ) as directory:
        binary = Path(directory) / "aapt2"
        provenance = download_pinned_binary(binary)

        candidate = prepare_aapt2_candidate.prepare_candidate(
            binary=binary,
            destination=destination,
            source_repository=SOURCE_REPOSITORY,
            source_revision=SOURCE_COMMIT,
            min_api=min_api,
        )

    evidence_file = destination / "upstream-provenance.json"
    _write_json_atomic(evidence_file, provenance)

    return {
        "status": "PINNED_SOURCE_CANDIDATE_READY",
        "trustGranted": False,
        "published": False,
        "source": provenance,
        "candidate": candidate,
        "candidateDirectory": str(destination),
        "provenanceFile": str(evidence_file),
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("destination", type=Path)
    parser.add_argument(
        "--min-api",
        type=int,
        default=31,
        help="Candidate Android minimum API. Android 12 corresponds to API 31.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    try:
        payload = acquire_candidate(
            args.destination,
            min_api=args.min_api,
        )
    except (
        AcquisitionError,
        prepare_aapt2_candidate.Aapt2CandidateError,
        OSError,
    ) as exc:
        print(f"AAPT2_ACQUISITION FAILED: {exc}", file=os.sys.stderr)
        return 2

    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
