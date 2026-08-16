"""Reference ETuMax-side client for the ETroute capability contract.

This file intentionally lives in ETroute until the ETuMax repository is
available for direct integration. It defines the transport-neutral client
surface ETuMax should consume.
"""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from typing import Any, Protocol


CONTRACT_VERSION = "1.0"


class Transport(Protocol):
    def send(self, payload: str) -> str:
        ...


@dataclass
class ClientResult:
    ok: bool
    capability: str
    backend: str
    request_id: str | None
    contract_version: str
    value: Any = None
    error: dict[str, Any] | None = None


class ETRouteClientError(RuntimeError):
    pass


class ETRouteClient:
    """Minimal ETuMax-facing client with one stable request path."""

    def __init__(self, transport: Transport) -> None:
        self.transport = transport

    def request(
        self,
        capability: str,
        payload: dict[str, Any],
        *,
        request_id: str | None = None,
    ) -> ClientResult:
        if not capability:
            raise ValueError("capability is required")
        if not isinstance(payload, dict):
            raise TypeError("payload must be a dict")

        request_id = request_id or str(uuid.uuid4())
        request = {
            "contract_version": CONTRACT_VERSION,
            "request_id": request_id,
            "capability": capability,
            "payload": payload,
        }

        raw = self.transport.send(json.dumps(request))
        try:
            response = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ETRouteClientError(f"ETroute returned invalid JSON: {exc}") from exc

        self._validate_response(response, capability, request_id)
        return ClientResult(
            ok=bool(response["ok"]),
            capability=str(response["capability"]),
            backend=str(response["backend"]),
            request_id=response.get("request_id"),
            contract_version=str(response["contract_version"]),
            value=response.get("value"),
            error=response.get("error"),
        )

    def plan_recipe(self, project: str, package: str | None = None, abis: list[str] | None = None) -> ClientResult:
        payload: dict[str, Any] = {"project": project, "abis": abis or ["arm64-v8a"]}
        if package:
            payload["package"] = package
        return self.request("p4a.recipe.plan", payload)

    def generate_recipe(
        self,
        project: str,
        *,
        output: str = "p4a-recipes",
        package: str | None = None,
        abis: list[str] | None = None,
    ) -> ClientResult:
        payload: dict[str, Any] = {
            "project": project,
            "output": output,
            "abis": abis or ["arm64-v8a"],
        }
        if package:
            payload["package"] = package
        return self.request("p4a.recipe.generate", payload)

    def plan_build(self, payload: dict[str, Any]) -> ClientResult:
        return self.request("p4a.build.plan", payload)

    def build(self, payload: dict[str, Any]) -> ClientResult:
        return self.request("p4a.build", payload)

    def verify(self, artifact: str, *, abis: list[str] | None = None) -> ClientResult:
        return self.request(
            "p4a.verify",
            {"artifact": artifact, "abis": abis or ["arm64-v8a"]},
        )

    @staticmethod
    def _validate_response(response: Any, capability: str, request_id: str) -> None:
        if not isinstance(response, dict):
            raise ETRouteClientError("ETroute response must be an object")
        required = {"ok", "capability", "backend", "contract_version"}
        missing = sorted(required - response.keys())
        if missing:
            raise ETRouteClientError(f"ETroute response missing fields: {', '.join(missing)}")
        if response["capability"] != capability:
            raise ETRouteClientError(
                f"capability mismatch: requested {capability}, received {response['capability']}"
            )
        if response.get("request_id") != request_id:
            raise ETRouteClientError(
                f"request_id mismatch: expected {request_id}, received {response.get('request_id')}"
            )
        remote_major = str(response["contract_version"]).split(".", 1)[0]
        local_major = CONTRACT_VERSION.split(".", 1)[0]
        if remote_major != local_major:
            raise ETRouteClientError(
                f"incompatible ETroute contract {response['contract_version']}"
            )


class InProcessGatewayTransport:
    """Test/local transport used when ETroute is importable in the same process."""

    def __init__(self, gateway: Any) -> None:
        self.gateway = gateway

    def send(self, payload: str) -> str:
        return self.gateway.handle_json(payload)
