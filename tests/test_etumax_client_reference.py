import json
import tempfile
import unittest
from pathlib import Path

from etroute_contract import ETRouteGateway
from etumax_client_reference import (
    ETRouteClient,
    ETRouteClientError,
    InProcessGatewayTransport,
)


class StaticTransport:
    def __init__(self, response: str):
        self.response = response
        self.sent = None

    def send(self, payload: str) -> str:
        self.sent = json.loads(payload)
        return self.response


class ETuMaxClientReferenceTests(unittest.TestCase):
    def test_end_to_end_plan_through_gateway(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "module.py").write_text("VALUE = 1\n", encoding="utf-8")
            client = ETRouteClient(InProcessGatewayTransport(ETRouteGateway()))
            result = client.plan_recipe(tmp, package="demo", abis=["arm64-v8a"])
            self.assertTrue(result.ok)
            self.assertEqual(result.capability, "p4a.recipe.plan")
            self.assertEqual(result.value["strategy"], "pip")
            self.assertEqual(result.request_id is not None, True)

    def test_request_id_is_sent_and_checked(self):
        response = json.dumps({
            "ok": True,
            "capability": "p4a.recipe.plan",
            "backend": "fake",
            "request_id": "abc",
            "contract_version": "1.0",
            "value": {},
        })
        transport = StaticTransport(response)
        client = ETRouteClient(transport)
        result = client.request("p4a.recipe.plan", {"project": "."}, request_id="abc")
        self.assertEqual(result.request_id, "abc")
        self.assertEqual(transport.sent["request_id"], "abc")

    def test_request_id_mismatch_is_rejected(self):
        response = json.dumps({
            "ok": True,
            "capability": "p4a.recipe.plan",
            "backend": "fake",
            "request_id": "wrong",
            "contract_version": "1.0",
            "value": {},
        })
        client = ETRouteClient(StaticTransport(response))
        with self.assertRaises(ETRouteClientError):
            client.request("p4a.recipe.plan", {"project": "."}, request_id="expected")

    def test_capability_mismatch_is_rejected(self):
        response = json.dumps({
            "ok": True,
            "capability": "p4a.verify",
            "backend": "fake",
            "request_id": "abc",
            "contract_version": "1.0",
            "value": {},
        })
        client = ETRouteClient(StaticTransport(response))
        with self.assertRaises(ETRouteClientError):
            client.request("p4a.recipe.plan", {"project": "."}, request_id="abc")

    def test_incompatible_contract_is_rejected(self):
        response = json.dumps({
            "ok": True,
            "capability": "p4a.recipe.plan",
            "backend": "fake",
            "request_id": "abc",
            "contract_version": "2.0",
            "value": {},
        })
        client = ETRouteClient(StaticTransport(response))
        with self.assertRaises(ETRouteClientError):
            client.request("p4a.recipe.plan", {"project": "."}, request_id="abc")

    def test_invalid_json_is_rejected(self):
        client = ETRouteClient(StaticTransport("not-json"))
        with self.assertRaises(ETRouteClientError):
            client.request("p4a.recipe.plan", {"project": "."}, request_id="abc")


if __name__ == "__main__":
    unittest.main()
