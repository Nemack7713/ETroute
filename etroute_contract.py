"""Transport-neutral ETuMax <-> ETroute capability contract."""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from typing import Any, Callable

from etroute_p4a import P4aCapabilityBackend

try:
    from p4a_build_backend import P4aBuildBackend
except ImportError:
    P4aBuildBackend = None


CONTRACT_VERSION = "1.0"
SUPPORTED_CAPABILITIES = {
    "p4a.recipe.plan",
    "p4a.recipe.generate",
    "p4a.build.plan",
    "p4a.build",
    "p4a.verify",
}


@dataclass(frozen=True)
class CapabilityRequest:
    capability: str
    payload: dict[str, Any]
    request_id: str | None = None
    contract_version: str = CONTRACT_VERSION


@dataclass
class CapabilityEnvelope:
    ok: bool
    capability: str
    backend: str
    request_id: str | None
    contract_version: str
    value: Any = None
    error: dict[str, Any] | None = None
    meta: dict[str, Any] = field(default_factory=dict)

    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2, default=str)


class ContractError(ValueError):
    pass


class ETRouteGateway:
    """Single stable entrypoint which ETuMax can call through any transport."""

    def __init__(self) -> None:
        self._routes: list[tuple[set[str], Any]] = [
            ({"p4a.recipe.plan", "p4a.recipe.generate"}, P4aCapabilityBackend()),
        ]
        if P4aBuildBackend is not None:
            self._routes.append(({"p4a.build.plan", "p4a.build", "p4a.verify"}, P4aBuildBackend()))

    def handle(self, request: CapabilityRequest | dict[str, Any]) -> CapabilityEnvelope:
        try:
            req = self._coerce(request)
            self._validate(req)
            backend = self._backend_for(req.capability)
            if backend is None:
                return self._failure(req, "capability_unavailable", "No backend is registered for this capability.")
            result = backend.resolve(req.capability, req.payload)
            if not result.available:
                return self._failure(req, "backend_unavailable", "Backend could not fulfill capability.", backend=result.backend, details=result.value)
            return CapabilityEnvelope(
                ok=True,
                capability=req.capability,
                backend=result.backend,
                request_id=req.request_id,
                contract_version=CONTRACT_VERSION,
                value=result.value,
                meta={"requested_contract_version": req.contract_version},
            )
        except ContractError as exc:
            capability = request.capability if isinstance(request, CapabilityRequest) else str(request.get("capability", ""))
            request_id = request.request_id if isinstance(request, CapabilityRequest) else request.get("request_id")
            return CapabilityEnvelope(
                ok=False,
                capability=capability,
                backend="etroute_gateway",
                request_id=request_id,
                contract_version=CONTRACT_VERSION,
                error={"code": "invalid_request", "message": str(exc)},
            )
        except Exception as exc:
            capability = request.capability if isinstance(request, CapabilityRequest) else str(request.get("capability", ""))
            request_id = request.request_id if isinstance(request, CapabilityRequest) else request.get("request_id")
            return CapabilityEnvelope(
                ok=False,
                capability=capability,
                backend="etroute_gateway",
                request_id=request_id,
                contract_version=CONTRACT_VERSION,
                error={"code": "internal_error", "message": str(exc), "type": type(exc).__name__},
            )

    def handle_json(self, raw: str) -> str:
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            return CapabilityEnvelope(
                ok=False,
                capability="",
                backend="etroute_gateway",
                request_id=None,
                contract_version=CONTRACT_VERSION,
                error={"code": "invalid_json", "message": str(exc)},
            ).to_json()
        return self.handle(data).to_json()

    def describe(self) -> dict[str, Any]:
        registered = sorted(cap for caps, _ in self._routes for cap in caps)
        return {
            "contract_version": CONTRACT_VERSION,
            "supported_capabilities": sorted(SUPPORTED_CAPABILITIES),
            "registered_capabilities": registered,
        }

    @staticmethod
    def _coerce(request: CapabilityRequest | dict[str, Any]) -> CapabilityRequest:
        if isinstance(request, CapabilityRequest):
            return request
        if not isinstance(request, dict):
            raise ContractError("request must be CapabilityRequest or mapping")
        return CapabilityRequest(
            capability=str(request.get("capability", "")),
            payload=request.get("payload", {}),
            request_id=request.get("request_id"),
            contract_version=str(request.get("contract_version", CONTRACT_VERSION)),
        )

    @staticmethod
    def _validate(request: CapabilityRequest) -> None:
        if not request.capability:
            raise ContractError("capability is required")
        if request.capability not in SUPPORTED_CAPABILITIES:
            raise ContractError(f"unsupported capability: {request.capability}")
        if not isinstance(request.payload, dict):
            raise ContractError("payload must be an object")
        if request.contract_version.split(".", 1)[0] != CONTRACT_VERSION.split(".", 1)[0]:
            raise ContractError(
                f"incompatible contract version {request.contract_version}; expected major version {CONTRACT_VERSION.split('.', 1)[0]}"
            )

    def _backend_for(self, capability: str) -> Any | None:
        for capabilities, backend in self._routes:
            if capability in capabilities:
                return backend
        return None

    @staticmethod
    def _failure(
        request: CapabilityRequest,
        code: str,
        message: str,
        *,
        backend: str = "etroute_gateway",
        details: Any = None,
    ) -> CapabilityEnvelope:
        error = {"code": code, "message": message}
        if details is not None:
            error["details"] = details
        return CapabilityEnvelope(
            ok=False,
            capability=request.capability,
            backend=backend,
            request_id=request.request_id,
            contract_version=CONTRACT_VERSION,
            error=error,
        )
