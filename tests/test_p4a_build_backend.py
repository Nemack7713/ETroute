import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from p4a_build_backend import P4aBuildBackend, P4aBuildRequest, VerificationStatus


class P4aBuildBackendTests(unittest.TestCase):
    def test_plan_builds_multi_abi_command_without_running_p4a(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "main.py").write_text("print('hello')\n", encoding="utf-8")
            backend = P4aBuildBackend()
            request = P4aBuildRequest(project=tmp, abis=["arm64-v8a", "x86_64"], requirements=["python3", "requests"])
            plan = backend.plan(request)
            command = plan["command"]
            self.assertEqual(plan["recipe_plan"]["strategy"], "pip")
            self.assertIn("--requirements", command)
            self.assertIn("python3,requests", command)
            self.assertEqual(command.count("--arch"), 2)

    def test_missing_p4a_is_reported_without_attempting_execution(self):
        with tempfile.TemporaryDirectory() as tmp, patch("p4a_build_backend.shutil.which", return_value=None):
            Path(tmp, "main.py").write_text("print('hello')\n", encoding="utf-8")
            result = P4aBuildBackend().build(P4aBuildRequest(project=tmp))
            self.assertEqual(result.status.value, "tool_unavailable")
            self.assertEqual(result.verification.value, "not_run")

    def test_verify_valid_apk_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp, "probe.apk")
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
                archive.writestr("lib/arm64-v8a/libprobe.so", b"native")
            status, details = P4aBuildBackend().verify_artifacts([apk], ["arm64-v8a"])
            self.assertEqual(status, VerificationStatus.VERIFIED)
            self.assertTrue(any("verified Android container" in item for item in details))

    def test_verify_rejects_missing_requested_abi(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp, "probe.apk")
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
                archive.writestr("lib/x86_64/libprobe.so", b"native")
            status, details = P4aBuildBackend().verify_artifacts([apk], ["arm64-v8a"])
            self.assertEqual(status, VerificationStatus.FAILED)
            self.assertTrue(any("missing requested native ABI" in item for item in details))

    def test_verify_requires_android_container_structure(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp, "bad.apk")
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("not-a-manifest.txt", b"bad")
            status, _ = P4aBuildBackend().verify_artifacts([apk], ["arm64-v8a"])
            self.assertEqual(status, VerificationStatus.FAILED)


if __name__ == "__main__":
    unittest.main()
