import tempfile
import unittest
from pathlib import Path

from etroute_policy import (
    Capability,
    Endpoint,
    NetworkMode,
    PolicyError,
    StagedWorkspace,
    TaskPolicy,
)


class ETRoutePolicyTests(unittest.TestCase):
    def test_offline_policy_rejects_network(self):
        policy = TaskPolicy(task_id="offline-task")
        with self.assertRaises(PolicyError):
            policy.authorize_endpoint(Endpoint("127.0.0.1", 8765))

    def test_loopback_policy_rejects_public_endpoint(self):
        policy = TaskPolicy(
            task_id="local-task",
            capabilities=frozenset({Capability.LOCAL_NETWORK}),
            network_mode=NetworkMode.LOOPBACK_ONLY,
        )
        with self.assertRaises(PolicyError):
            policy.authorize_endpoint(Endpoint("8.8.8.8", 53))

    def test_path_authorization_rejects_escape(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as outside:
            policy = TaskPolicy(
                task_id="storage-task",
                capabilities=frozenset({Capability.STORAGE_READ}),
                allowed_roots=(Path(root),),
            )
            with self.assertRaises(PolicyError):
                policy.authorize_path(Path(outside) / "file.txt")

    def test_staged_workspace_promotes_without_journal(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source"
            stage_parent = root / "staging"
            destination = root / "destination"
            source.mkdir()
            (source / "example.txt").write_text("before", encoding="utf-8")

            workspace = StagedWorkspace(source, stage_parent)
            stage = workspace.create()
            workspace.record_replace("example.txt")
            (stage / "example.txt").write_text("after", encoding="utf-8")
            workspace.promote(destination)

            self.assertEqual((destination / "example.txt").read_text(encoding="utf-8"), "after")
            self.assertFalse((destination / ".etroute-journal").exists())


if __name__ == "__main__":
    unittest.main()
