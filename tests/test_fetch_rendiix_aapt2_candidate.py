#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from tools import fetch_rendiix_aapt2_candidate as fetcher


class RendiixAapt2FetcherTests(unittest.TestCase):
    def test_pinned_source_identity_is_immutable(self):
        self.assertEqual(
            fetcher.SOURCE_REPOSITORY,
            "rendiix/termux-aapt",
        )
        self.assertEqual(
            fetcher.SOURCE_COMMIT,
            "8d981d41c1228e0ea2733e6d4bfc039d182d1b0f",
        )
        self.assertEqual(
            fetcher.SOURCE_PATH,
            "prebuilt-binary-android-12+/arm64/aapt2",
        )
        self.assertEqual(
            fetcher.SOURCE_BLOB_SHA1,
            "317ef4b90fef2e5921901447a8463c268fb19bd5",
        )
        self.assertEqual(fetcher.SOURCE_SIZE_BYTES, 3_498_816)

    def test_git_blob_sha1_matches_git_object_format(self):
        payload = b"hello\n"
        expected = hashlib.sha1(b"blob 6\0hello\n").hexdigest()
        self.assertEqual(fetcher.git_blob_sha1(payload), expected)

    def test_download_rejects_wrong_size(self):
        original_urlopen = fetcher.urllib.request.urlopen

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self, _size=-1):
                if getattr(self, "done", False):
                    return b""
                self.done = True
                return b"too-small"

        fetcher.urllib.request.urlopen = lambda *_args, **_kwargs: Response()
        try:
            with tempfile.TemporaryDirectory() as directory:
                destination = Path(directory) / "aapt2"
                with self.assertRaises(fetcher.AcquisitionError):
                    fetcher.download_pinned_binary(destination)
                self.assertFalse(destination.exists())
        finally:
            fetcher.urllib.request.urlopen = original_urlopen

    def test_download_rejects_wrong_blob_identity(self):
        original_urlopen = fetcher.urllib.request.urlopen
        original_size = fetcher.SOURCE_SIZE_BYTES
        original_blob = fetcher.SOURCE_BLOB_SHA1
        payload = b"fixture-payload"

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self, _size=-1):
                if getattr(self, "done", False):
                    return b""
                self.done = True
                return payload

        fetcher.urllib.request.urlopen = lambda *_args, **_kwargs: Response()
        fetcher.SOURCE_SIZE_BYTES = len(payload)
        fetcher.SOURCE_BLOB_SHA1 = "0" * 40
        try:
            with tempfile.TemporaryDirectory() as directory:
                destination = Path(directory) / "aapt2"
                with self.assertRaises(fetcher.AcquisitionError):
                    fetcher.download_pinned_binary(destination)
                self.assertFalse(destination.exists())
        finally:
            fetcher.urllib.request.urlopen = original_urlopen
            fetcher.SOURCE_SIZE_BYTES = original_size
            fetcher.SOURCE_BLOB_SHA1 = original_blob


if __name__ == "__main__":
    unittest.main()
