#!/usr/bin/env python3
"""Generation-aware ETuMax <-> ETroute runtime lifecycle contract.

This module contains no Android, JNI, PRoot, rootfs, or transport logic. It
models the authoritative lifecycle messages exchanged between the ETuMax
control plane and the ETroute Linux runtime adapter.
"""
from __future__ import annotations

import json
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Mapping

RUNTIME_SCHEMA_VERSION = 1
MAX_TEXT = 256
MAX_DIAGNOSTICS_JSON_BYTES = 128 * 1024


class RuntimeContractError(ValueError):
    pass


class RuntimeCommand(str, Enum):
    START = "start"
    STOP = "stop"
    PROBE = "probe"
    DIAGNOSTICS = "diagnostics"


class RuntimeState(str, Enum):
    DORMANT = "dormant"
    STARTING = "starting"
    RUNNING = "running"
    STOPPING = "stopping"
    FAILED = "failed"


class BackendLifecycle(str, Enum):
    NOT_INITIALIZED = "not_initialized"
    INITIALIZING = "initializing"
    READY = "ready"
    ACTIVE = "active"
    RESIDENT_INACTIVE = "resident_inactive"
    FAILED = "failed"
    STOPPED = "stopped"


class RuntimeStage(str, Enum):
    RECEIVED = "received"
    ROOTFS_SELECTING = "rootfs_selecting"
    ROOTFS_VERIFYING = "rootfs_verifying"
    ROOTFS_VERIFIED = "rootfs_verified"
    ENGINE_STARTING = "engine_starting"
    ROOTFS_ENTERED = "rootfs_entered"
    PROBING = "probing"
    READY = "ready"
    RUNNING = "running"
    STOPPING = "stopping"
    STOPPED = "stopped"
    FAILED_ROOTFS_SELECT = "failed_rootfs_select"
    FAILED_ROOTFS_VERIFY = "failed_rootfs_verify"
    FAILED_ENGINE_START = "failed_engine_start"
    FAILED_ROOTFS_ENTER = "failed_rootfs_enter"
    FAILED_PROBE = "failed_probe"
    FAILED_HANDSHAKE = "failed_handshake"
    FAILED_EXECUTION = "failed_execution"


class BackendId(str, Enum):
    CHAQUOPY = "chaquopy"
    NATIVE_ANDROID = "native_android"
    ETROUTE = "etroute"


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _require_text(name: str, value: str, *, maximum: int = MAX_TEXT) -> str:
    cleaned = str(value).strip()
    if not cleaned:
        raise RuntimeContractError(f"{name} cannot be empty")
    if len(cleaned) > maximum:
        raise RuntimeContractError(f"{name} exceeds {maximum} characters")
    return cleaned


def _require_generation(value: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise RuntimeContractError("generation must be an integer")
    if value < 0:
        raise RuntimeContractError("generation cannot be negative")
    return value


def _validate_mapping(name: str, value: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        raise RuntimeContractError(f"{name} must be a JSON object")
    copied = dict(value)
    try:
        encoded = json.dumps(
            copied,
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise RuntimeContractError(f"{name} is not JSON serializable: {exc}") from exc
    if len(encoded) > MAX_DIAGNOSTICS_JSON_BYTES:
        raise RuntimeContractError(
            f"{name} exceeds {MAX_DIAGNOSTICS_JSON_BYTES} encoded bytes"
        )
    return copied


@dataclass(frozen=True)
class RuntimeRequest:
    command: RuntimeCommand | str
    generation: int
    request_id: str = field(default_factory=lambda: f"runreq-{uuid.uuid4().hex}")
    session_id: str = field(default_factory=lambda: f"session-{uuid.uuid4().hex}")
    preferred_backend: BackendId | str = BackendId.ETROUTE
    rootfs_profile: str | None = None
    requirements: tuple[str, ...] = ()
    options: Mapping[str, Any] = field(default_factory=dict)
    created_at: str = field(default_factory=utc_now_iso)
    schema_version: int = RUNTIME_SCHEMA_VERSION

    def __post_init__(self) -> None:
        if self.schema_version != RUNTIME_SCHEMA_VERSION:
            raise RuntimeContractError(
                f"unsupported runtime request schema_version: {self.schema_version}"
            )
        object.__setattr__(self, "command", RuntimeCommand(self.command))
        object.__setattr__(self, "generation", _require_generation(self.generation))
        object.__setattr__(self, "request_id", _require_text("request_id", self.request_id))
        object.__setattr__(self, "session_id", _require_text("session_id", self.session_id))
        object.__setattr__(self, "preferred_backend", BackendId(self.preferred_backend))
        object.__setattr__(self, "options", _validate_mapping("options", self.options))

        if self.rootfs_profile is not None:
            object.__setattr__(
                self,
                "rootfs_profile",
                _require_text("rootfs_profile", self.rootfs_profile, maximum=128),
            )

        normalized_requirements: list[str] = []
        for requirement in self.requirements:
            normalized_requirements.append(
                _require_text("requirement", requirement, maximum=128).lower()
            )
        object.__setattr__(self, "requirements", tuple(normalized_requirements))

        try:
            datetime.fromisoformat(self.created_at)
        except ValueError as exc:
            raise RuntimeContractError("created_at must be ISO-8601") from exc

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["command"] = self.command.value
        result["preferred_backend"] = self.preferred_backend.value
        result["requirements"] = list(self.requirements)
        return result

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), separators=(",", ":"), sort_keys=True)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "RuntimeRequest":
        if not isinstance(value, Mapping):
            raise RuntimeContractError("runtime request JSON root must be an object")
        data = dict(value)
        allowed = {
            "schema_version",
            "command",
            "generation",
            "request_id",
            "session_id",
            "preferred_backend",
            "rootfs_profile",
            "requirements",
            "options",
            "created_at",
        }
        unknown = sorted(set(data) - allowed)
        if unknown:
            raise RuntimeContractError(
                f"unknown runtime request fields: {', '.join(unknown)}"
            )
        if "command" not in data:
            raise RuntimeContractError("runtime request field 'command' is required")
        if "generation" not in data:
            raise RuntimeContractError("runtime request field 'generation' is required")
        if "requirements" in data:
            data["requirements"] = tuple(data["requirements"])
        return cls(**data)

    @classmethod
    def from_json(cls, text: str) -> "RuntimeRequest":
        try:
            value = json.loads(text)
        except json.JSONDecodeError as exc:
            raise RuntimeContractError(f"invalid runtime request JSON: {exc}") from exc
        return cls.from_dict(value)


@dataclass(frozen=True)
class RuntimeStatus:
    generation: int
    request_id: str
    session_id: str
    state: RuntimeState | str
    backend: BackendId | str
    backend_lifecycle: BackendLifecycle | str
    stage: RuntimeStage | str
    ready: bool
    probe_ok: bool
    alive: bool
    rootfs_profile: str | None = None
    execution_id: str | None = None
    message: str | None = None
    diagnostics: Mapping[str, Any] = field(default_factory=dict)
    updated_at: str = field(default_factory=utc_now_iso)
    schema_version: int = RUNTIME_SCHEMA_VERSION

    def __post_init__(self) -> None:
        if self.schema_version != RUNTIME_SCHEMA_VERSION:
            raise RuntimeContractError(
                f"unsupported runtime status schema_version: {self.schema_version}"
            )
        object.__setattr__(self, "generation", _require_generation(self.generation))
        object.__setattr__(self, "request_id", _require_text("request_id", self.request_id))
        object.__setattr__(self, "session_id", _require_text("session_id", self.session_id))
        object.__setattr__(self, "state", RuntimeState(self.state))
        object.__setattr__(self, "backend", BackendId(self.backend))
        object.__setattr__(
            self,
            "backend_lifecycle",
            BackendLifecycle(self.backend_lifecycle),
        )
        object.__setattr__(self, "stage", RuntimeStage(self.stage))
        object.__setattr__(
            self,
            "diagnostics",
            _validate_mapping("diagnostics", self.diagnostics),
        )

        for name in ("ready", "probe_ok", "alive"):
            if not isinstance(getattr(self, name), bool):
                raise RuntimeContractError(f"{name} must be boolean")

        if self.rootfs_profile is not None:
            object.__setattr__(
                self,
                "rootfs_profile",
                _require_text("rootfs_profile", self.rootfs_profile, maximum=128),
            )
        if self.execution_id is not None:
            object.__setattr__(
                self,
                "execution_id",
                _require_text("execution_id", self.execution_id),
            )
        if self.message is not None:
            object.__setattr__(
                self,
                "message",
                str(self.message).strip() or None,
            )

        if self.state is RuntimeState.RUNNING and not (
            self.ready and self.probe_ok and self.alive
        ):
            raise RuntimeContractError(
                "RUNNING requires ready=true, probe_ok=true, and alive=true"
            )
        if self.ready and self.stage not in {RuntimeStage.READY, RuntimeStage.RUNNING}:
            raise RuntimeContractError(
                "ready=true requires stage ready or running"
            )
        if self.stage is RuntimeStage.RUNNING and self.state is not RuntimeState.RUNNING:
            raise RuntimeContractError(
                "stage=running requires state=running"
            )

        try:
            datetime.fromisoformat(self.updated_at)
        except ValueError as exc:
            raise RuntimeContractError("updated_at must be ISO-8601") from exc

    @property
    def authoritative_running(self) -> bool:
        return (
            self.state is RuntimeState.RUNNING
            and self.ready
            and self.probe_ok
            and self.alive
        )

    def matches(self, request: RuntimeRequest) -> bool:
        return (
            self.generation == request.generation
            and self.request_id == request.request_id
            and self.session_id == request.session_id
        )

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["state"] = self.state.value
        result["backend"] = self.backend.value
        result["backend_lifecycle"] = self.backend_lifecycle.value
        result["stage"] = self.stage.value
        result["authoritative_running"] = self.authoritative_running
        return result

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), separators=(",", ":"), sort_keys=True)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "RuntimeStatus":
        if not isinstance(value, Mapping):
            raise RuntimeContractError("runtime status JSON root must be an object")
        data = dict(value)
        data.pop("authoritative_running", None)
        allowed = {
            "schema_version",
            "generation",
            "request_id",
            "session_id",
            "state",
            "backend",
            "backend_lifecycle",
            "stage",
            "ready",
            "probe_ok",
            "alive",
            "rootfs_profile",
            "execution_id",
            "message",
            "diagnostics",
            "updated_at",
        }
        unknown = sorted(set(data) - allowed)
        if unknown:
            raise RuntimeContractError(
                f"unknown runtime status fields: {', '.join(unknown)}"
            )
        required = {
            "generation",
            "request_id",
            "session_id",
            "state",
            "backend",
            "backend_lifecycle",
            "stage",
            "ready",
            "probe_ok",
            "alive",
        }
        missing = sorted(required - set(data))
        if missing:
            raise RuntimeContractError(
                f"missing runtime status fields: {', '.join(missing)}"
            )
        return cls(**data)

    @classmethod
    def from_json(cls, text: str) -> "RuntimeStatus":
        try:
            value = json.loads(text)
        except json.JSONDecodeError as exc:
            raise RuntimeContractError(f"invalid runtime status JSON: {exc}") from exc
        return cls.from_dict(value)


def starting_status(
    request: RuntimeRequest,
    *,
    stage: RuntimeStage = RuntimeStage.RECEIVED,
    rootfs_profile: str | None = None,
    diagnostics: Mapping[str, Any] | None = None,
) -> RuntimeStatus:
    return RuntimeStatus(
        generation=request.generation,
        request_id=request.request_id,
        session_id=request.session_id,
        state=RuntimeState.STARTING,
        backend=BackendId.ETROUTE,
        backend_lifecycle=BackendLifecycle.INITIALIZING,
        stage=stage,
        ready=False,
        probe_ok=False,
        alive=False,
        rootfs_profile=rootfs_profile,
        diagnostics=diagnostics or {},
    )


def running_status(
    request: RuntimeRequest,
    *,
    rootfs_profile: str,
    execution_id: str,
    diagnostics: Mapping[str, Any] | None = None,
) -> RuntimeStatus:
    return RuntimeStatus(
        generation=request.generation,
        request_id=request.request_id,
        session_id=request.session_id,
        state=RuntimeState.RUNNING,
        backend=BackendId.ETROUTE,
        backend_lifecycle=BackendLifecycle.ACTIVE,
        stage=RuntimeStage.RUNNING,
        ready=True,
        probe_ok=True,
        alive=True,
        rootfs_profile=rootfs_profile,
        execution_id=execution_id,
        diagnostics=diagnostics or {},
    )


def failed_status(
    request: RuntimeRequest,
    *,
    stage: RuntimeStage,
    message: str,
    rootfs_profile: str | None = None,
    alive: bool = False,
    diagnostics: Mapping[str, Any] | None = None,
) -> RuntimeStatus:
    if not stage.value.startswith("failed_"):
        raise RuntimeContractError("failed_status requires a failed_* stage")
    return RuntimeStatus(
        generation=request.generation,
        request_id=request.request_id,
        session_id=request.session_id,
        state=RuntimeState.FAILED,
        backend=BackendId.ETROUTE,
        backend_lifecycle=BackendLifecycle.FAILED,
        stage=stage,
        ready=False,
        probe_ok=False,
        alive=alive,
        rootfs_profile=rootfs_profile,
        message=message,
        diagnostics=diagnostics or {},
    )
