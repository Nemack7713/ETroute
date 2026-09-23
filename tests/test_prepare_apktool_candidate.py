#!/usr/bin/env python3
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools import prepare_apktool_candidate as candidate


def release_fixture(
    *,
    tag: str = "v3.0.3",
    digest: str = "sha256:" + ("a" * 64),
    size: int = 123,
    url: str | None = None,
):
    version = tag.removeprefix("v")
    name = f"apktool_{version}.jar"
    return {
        "tag_name": tag,
        "draft": False,
        "prerelease": False,
        "assets": [
            {
                "name": name,
                "digest": digest,
                "size": size,
                "browser_download_url": (
                    url
                    or f"https://github.com/iBotPeaches/Apktool/releases/download/{tag}/{name}"
                ),
            }
        ],
    }


class ApktoolCandidateTests(unittest.TestCase):
    def test_select_release_asset_accepts_official_digest(self):
        tag, name, digest, size = candidate.select_release_asset(
            release_fixture(),
            expected_tag="v3.0.3",
        )

        self.assertEqual(tag, "v3.0.3")
        self.assertEqual(name, "apktool_3.0.3.jar")
        self.assertEqual(digest, "a" * 64)
        self.assertEqual(size, 123)

    def test_select_release_asset_rejects_unexpected_host(self):
        fixture = release_fixture(
            url=(
                "https://example.com/iBotPeaches/Apktool/releases/download/"
                "v3.0.3/apktool_3.0.3.jar"
            )
        )

        with self.assertRaises(candidate.CandidateError):
            candidate.select_release_asset(fixture)

    def test_select_release_asset_requires_sha256_digest(self):
        with self.assertRaises(candidate.CandidateError):
            candidate.select_release_asset(
                release_fixture(digest="")
            )

    def test_candidate_manifest_declares_java_dependency_without_trust(self):
        manifest = candidate.build_candidate_manifest(
            tag="v3.0.3",
            asset_name="apktool_3.0.3.jar",
            sha256="b" * 64,
            size=100,
            java_pack_id="org.etroute.java",
            aapt2_pack_id="org.etroute.aapt2",
        )

        self.assertEqual(manifest["packId"], "org.etroute.apktool")
        self.assertEqual(manifest["provenance"], "EXTERNAL_VERIFIED")
        self.assertEqual(manifest["tools"][0]["kind"], "JAVA_JAR")
        self.assertEqual(
            manifest["dependencies"][0]["packId"],
            "org.etroute.java",
        )
        self.assertIsNone(
            manifest["dependencies"][0]["requiredGenerationId"]
        )
        self.assertEqual(
            manifest["dependencies"][1]["packId"],
            "org.etroute.aapt2",
        )
        self.assertIsNone(
            manifest["dependencies"][1]["requiredGenerationId"]
        )

    def test_download_verification_rejects_digest_mismatch_without_publish(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "artifact.jar"

            original_urlopen = candidate.urllib.request.urlopen

            class Response:
                def __enter__(self):
                    return self

                def __exit__(self, *_):
                    return False

                def read(self, _size=-1):
                    if getattr(self, "done", False):
                        return b""
                    self.done = True
                    return b"payload"

            candidate.urllib.request.urlopen = lambda *_args, **_kwargs: Response()
            try:
                with self.assertRaises(candidate.CandidateError):
                    candidate.download_verified_asset(
                        "https://github.com/iBotPeaches/Apktool/releases/download/"
                        "v3.0.3/apktool_3.0.3.jar",
                        destination,
                        expected_sha256="0" * 64,
                        expected_size=len(b"payload"),
                    )
            finally:
                candidate.urllib.request.urlopen = original_urlopen

            self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()
