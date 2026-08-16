import json
import tempfile
import unittest
from pathlib import Path

from etroute_contract import CONTRACT_VERSION, CapabilityRequest, ETRouteGateway


class ETRouteContractTests(unittest.TestCase):
    def setUp(self):
        self.gateway = ETRouteGateway()

    def test_describe_exposes_versioned_capabilities(self):
        description = self.gateway.describe()
        self.assertEqual(description["contract_version"], CONTRACT_VERSION)
        self.assertIn("p4a.recipe.plan", description["registered_capabilities"])

    def test_recipe_plan_preserves_request_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "module.py").write_text("VALUE = 1\n", encoding="utf-8")
            result = self.gateway.handle(CapabilityRequest(
                capability="p4a.recipe.plan",
                request_id="etumax-001",
                payload={"project": tmp, "package": "demo", "abis": ["arm64-v8a"]},
            ))
            self.assertTrue(result.ok)
            self.assertEqual(result.request_id, "etumax-001")
            self.assertEqual(result.value["strategy"], "pip")

    def test_json_transport_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "pyproject.toml").write_text("[build-system]\n", encoding="utf-8")
            raw = json.dumps({
                "contract_version": "1.0",
                "request_id": "android-42",
                "capability": "p4a.recipe.plan",
                "payload": {"project": tmp, "package": "modern"},
            })
            result = json.loads(self.gateway.handle_json(raw))
            self.assertTrue(result["ok"])
            self.assertEqual(result["request_id"], "android-42")
            self.assertEqual(result["value"]["strategy"], "PyProjectRecipe")

    def test_invalid_json_returns_structured_error(self):
        result = json.loads(self.gateway.handle_json("{"))
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"]["code"], "invalid_json")

    def test_unknown_capability_is_rejected_at_contract_boundary(self):
        result = self.gateway.handle({"capability": "shell.anything", "payload": {}})
        self.assertFalse(result.ok)
        self.assertEqual(result.error["code"], "invalid_request")

    def test_incompatible_major_version_is_rejected(self):
        result = self.gateway.handle({
            "contract_version": "2.0",
            "capability": "p4a.recipe.plan",
            "payload": {},
        })
        self.assertFalse(result.ok)
        self.assertEqual(result.error["code"], "invalid_request")


if __name__ == "__main__":
    unittest.main()
