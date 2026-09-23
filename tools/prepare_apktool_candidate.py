#!/usr/bin/env python3
"""Prepare a verified Apktool RuntimePack candidate from official upstream.

The script stages an upstream Apktool release artifact and evidence only. It
never grants ETroute execution trust and never publishes an active generation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Mapping, Sequence

LATEST_RELEASE_API = "https://api.github.com/repos/iBotPeaches/Apktool/releases/latest"
UPSTREAM_REPOSITORY = "iBotPeaches/Apktool"
MAX_JAR_BYTES = 64 * 1024 * 1024
USER_AGENT = "ETroute-Apktool-Candidate/1"

TAG_RE = re.compile(r"^v([0-9]+(?:\.[0-9]+){1,3}(?:[-+._A-Za-z0-9]*)?)$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class CandidateError(RuntimeError):
    pass


def _request_json(url: str, *, timeout: float = 30.0) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": USER_AGENT,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read(MAX_JAR_BYTES + 1)
    except OSError as exc:
        raise CandidateError(f"unable to fetch {url}: {exc}") from exc

    if len(payload) > MAX_JAR_BYTES:
        raise CandidateError("release metadata exceeded maximum size")

    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CandidateError("release metadata was not valid UTF-8 JSON") from exc

    if not isinstance(value, dict):
        raise CandidateError("release metadata root must be an object")
    return value


def _safe_asset_url(url: str, *, tag: str, asset_name: str) -> str:
    parsed = urllib.parse.urlparse(url)
    expected_path = f"/iBotPeaches/Apktool/releases/download/{tag}/{asset_name}"
    if (
        parsed.scheme != "https"
        or parsed.hostname != "github.com"
        or parsed.path != expected_path
        or parsed.params
        or parsed.query
        or parsed.fragment
    ):
        raise CandidateError(f"unexpected Apktool asset URL: {url}")
    return url


def select_release_asset(
    release: Mapping[str, Any],
    *,
    expected_tag: str | None = None,
) -> tuple[str, str, str, int]:
    tag = str(release.get("tag_name", "")).strip()
    match = TAG_RE.fullmatch(tag)
    if match is None:
        raise CandidateError(f"unexpected Apktool release tag: {tag!r}")
    if expected_tag is not None and tag != expected_tag:
        raise CandidateError(
            f"latest Apktool tag {tag!r} did not match expected {expected_tag!r}"
        )
    if release.get("draft") is True or release.get("prerelease") is True:
        raise CandidateError("refusing draft/prerelease Apktool release")

    version = match.group(1)
    expected_name = f"apktool_{version}.jar"
    assets = release.get("assets")
    if not isinstance(assets, list):
        raise CandidateError("release assets must be a list")

    candidates = [
        asset for asset in assets
        if isinstance(asset, Mapping) and asset.get("name") == expected_name
    ]
    if len(candidates) != 1:
        raise CandidateError(
            f"expected exactly one release asset named {expected_name!r}"
        )

    asset = candidates[0]
    digest_text = str(asset.get("digest", "")).strip().lower()
    if not digest_text.startswith("sha256:"):
        raise CandidateError("official release asset is missing a SHA-256 digest")
    digest = digest_text.removeprefix("sha256:")
    if SHA256_RE.fullmatch(digest) is None:
        raise CandidateError("official release asset SHA-256 digest is invalid")

    try:
        size = int(asset["size"])
    except (KeyError, TypeError, ValueError) as exc:
        raise CandidateError("official release asset size is missing/invalid") from exc
    if size <= 0 or size > MAX_JAR_BYTES:
        raise CandidateError(f"official Apktool JAR size is outside policy: {size}")

    url = _safe_asset_url(
        str(asset.get("browser_download_url", "")),
        tag=tag,
        asset_name=expected_name,
    )
    return tag, expected_name, digest, size


def download_verified_asset(
    url: str,
    destination: Path,
    *,
    expected_sha256: str,
    expected_size: int,
    timeout: float = 60.0,
) -> str:
    if SHA256_RE.fullmatch(expected_sha256) is None:
        raise CandidateError("expected_sha256 must be lowercase SHA-256 hex")

    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT},
    )
    digest = hashlib.sha256()
    written = 0

    with tempfile.NamedTemporaryFile(
        prefix=f".{destination.name}.",
        suffix=".tmp",
        dir=destination.parent,
        delete=False,
    ) as temporary:
        temporary_path = Path(temporary.name)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                while True:
                    chunk = response.read(128 * 1024)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > MAX_JAR_BYTES:
                        raise CandidateError("download exceeded Apktool JAR size policy")
                    digest.update(chunk)
                    temporary.write(chunk)
            temporary.flush()
            os.fsync(temporary.fileno())
        except BaseException:
            temporary_path.unlink(missing_ok=True)
            raise

    actual_sha256 = digest.hexdigest()
    if written != expected_size:
        temporary_path.unlink(missing_ok=True)
        raise CandidateError(
            f"Apktool JAR size mismatch: expected={expected_size} actual={written}"
        )
    if actual_sha256 != expected_sha256:
        temporary_path.unlink(missing_ok=True)
        raise CandidateError(
            f"Apktool JAR digest mismatch: expected={expected_sha256} actual={actual_sha256}"
        )

    os.replace(temporary_path, destination)
    return actual_sha256


def _write_json_atomic(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(value, indent=2, sort_keys=True) + "\n"
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(rendered, encoding="utf-8")
    os.replace(temporary, path)


def build_candidate_manifest(
    *,
    tag: str,
    asset_name: str,
    sha256: str,
    size: int,
    java_pack_id: str,
    aapt2_pack_id: str,
) -> dict[str, Any]:
    version = tag.removeprefix("v")
    return {
        "schemaVersion": 1,
        "packId": "org.etroute.apktool",
        "version": version,
        "provenance": "EXTERNAL_VERIFIED",
        "minApi": 24,
        "abis": [],
        "source": {
            "repository": UPSTREAM_REPOSITORY,
            "revision": tag,
            "assetName": asset_name,
            "assetSha256": sha256,
        },
        "artifacts": [
            {
                "path": f"artifacts/{asset_name}",
                "sha256": sha256,
                "sizeBytes": size,
                "executable": False,
            }
        ],
        "dependencies": [
            {
                "packId": java_pack_id,
                "requiredGenerationId": None,
            },
            {
                "packId": aapt2_pack_id,
                "requiredGenerationId": None,
            }
        ],
        "tools": [
            {
                "toolId": "apktool",
                "kind": "JAVA_JAR",
                "artifactPath": f"artifacts/{asset_name}",
            }
        ],
    }


def prepare_candidate(
    destination: Path,
    *,
    expected_tag: str | None = None,
    java_pack_id: str = "org.etroute.java",
    aapt2_pack_id: str = "org.etroute.aapt2",
) -> dict[str, Any]:
    release = _request_json(LATEST_RELEASE_API)
    tag, asset_name, digest, size = select_release_asset(
        release,
        expected_tag=expected_tag,
    )
    asset = next(
        item for item in release["assets"]
        if isinstance(item, Mapping) and item.get("name") == asset_name
    )
    asset_url = _safe_asset_url(
        str(asset["browser_download_url"]),
        tag=tag,
        asset_name=asset_name,
    )

    destination = destination.expanduser().resolve()
    staging = destination.with_name(f".{destination.name}.staging")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    try:
        jar_path = staging / "artifacts" / asset_name
        actual_digest = download_verified_asset(
            asset_url,
            jar_path,
            expected_sha256=digest,
            expected_size=size,
        )

        manifest = build_candidate_manifest(
            tag=tag,
            asset_name=asset_name,
            sha256=actual_digest,
            size=size,
            java_pack_id=java_pack_id,
            aapt2_pack_id=aapt2_pack_id,
        )
        evidence = {
            "status": "VERIFIED_CANDIDATE",
            "trustGranted": False,
            "published": False,
            "upstreamRepository": UPSTREAM_REPOSITORY,
            "releaseTag": tag,
            "assetName": asset_name,
            "assetSha256": actual_digest,
            "assetSizeBytes": size,
            "sourceApi": LATEST_RELEASE_API,
        }

        _write_json_atomic(staging / "candidate-manifest.json", manifest)
        _write_json_atomic(staging / "candidate-evidence.json", evidence)
        _write_json_atomic(staging / "upstream-release.json", dict(release))

        if destination.exists():
            raise CandidateError(
                f"destination already exists; refusing mutable replacement: {destination}"
            )
        os.replace(staging, destination)
        return evidence
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("destination", type=Path)
    parser.add_argument("--expected-tag")
    parser.add_argument("--java-pack-id", default="org.etroute.java")
    parser.add_argument("--aapt2-pack-id", default="org.etroute.aapt2")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        evidence = prepare_candidate(
            args.destination,
            expected_tag=args.expected_tag,
            java_pack_id=args.java_pack_id,
            aapt2_pack_id=args.aapt2_pack_id,
        )
    except CandidateError as exc:
        print(f"APKTOOL_CANDIDATE FAILED: {exc}", file=os.sys.stderr)
        return 2

    print(json.dumps(evidence, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
