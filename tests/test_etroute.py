import os
import tempfile
import unittest
from pathlib import Path

import etroute


class ETRouteTests(unittest.TestCase):
    def test_name_validation_rejects_traversal(self):
        with self.assertRaises(etroute.ETRouteError):
            etroute.validate_name("../escape")

    def test_bind_no_dereference_serialization(self):
        with tempfile.TemporaryDirectory() as tmp:
            bind = etroute.BindMount(Path(tmp), "/mnt/shared", True)
            self.assertTrue(bind.serialize().endswith(":/mnt/shared!"))

    def test_package_mode_rejects_host_group_bind(self):
        if not Path("/etc/group").exists():
            self.skipTest("host has no /etc/group")
        bind = etroute.BindMount(Path("/etc/group"), "/etc/group")
        with self.assertRaises(etroute.ETRouteError):
            etroute.validate_package_extra_binds([bind])

    def test_expected_policy_requires_home(self):
        old = os.environ.pop("HOME", None)
        try:
            with self.assertRaises(etroute.ETRouteError):
                etroute.package_policy_binds()
        finally:
            if old is not None:
                os.environ["HOME"] = old


if __name__ == "__main__":
    unittest.main()
