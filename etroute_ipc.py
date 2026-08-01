#!/usr/bin/env python3
"""Versioned ETroute-owned IPC envelope contract.

This module defines message validation and serialization only. It does not
launch ETumax, manage ETumax processes, or implement the ETumax runtime.
"""
from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Mapping

PROTOCOL_VERSION = 1
ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class IPCError(ValueError):
    pass


class Action(str, Enum):
    PING = "ping"
    GET_STATUS = "get_status"
    START_SESSION = "start_session"
    STOP_SESSION = "stop_session"
    RUN_PROJECT = "run_project"


class ErrorCode(str, Enum):
    INVALID_MESSAGE = "invalid_message"
    UNSUPPORTED_VERSION = "unsupported_version"
    UNAUTHORIZED = "unauthorized"
    POLICY_DENIED = "policy_denied"
    NOT_FOUND = "not_found"
    CONFLICT = "conflict"
    TIMEOUT = "timeout"
    RUNTIME_FAILURE = "runtime_failure"


def _timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


def _validate_id(name: str, value: str) -> str:
    if not isinstance(value, str) or not ID_RE.fullmatch(value):
        raise IPCError(f"{name} must match {ID_RE.pattern}")
    return value


def _validate_payload(payload: Mapping[str, Any] | None) -> dict[str, Any]:
    if payload is None:
        return {}
    if not isinstance(payload, Mapping):
        raise IPCError("payload must be an object")
    return dict(payload)


@dataclass(frozen=True)
class RequestEnvelope:
    action: Action
    request_id: str
    session_id: str
    payload: dict[str, Any] = field(default_factory=dict)
    version: int = PROTOCOL_VERSION
    caller: str = "etroute"
    created_at: str = field(default_factory=_timestamp)

    def validate(self) -> "RequestEnvelope":
        if self.version != PROTOCOL_VERSION:
            raise IPCError(f"unsupported protocol version: {self.version}")
        if self.caller != "etroute":
            raise IPCError("request caller must be etroute")
        return RequestEnvelope(
            action=Action(self.action),
            request_id=_validate_id("request_id", self.request_id),
            session_id=_validate_id("session_id", self.session_id),
            payload=_validate_payload(self.payload),
            version=self.version,
            caller=self.caller,
            created_at=self.created_at,
        )

    def to_json(self) -> str:
        value = asdict(self.validate())
        value["action"] = self.action.value
        return json.dumps(value, separators=(",", ":"), sort_keys=True)

    @classmethod
    def from_json(cls, raw: str) -> "RequestEnvelope":
        try:
            value = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise IPCError(f"invalid JSON: {exc}") from exc
        if not isinstance(value, dict):
            raise IPCError("request root must be an object")
        try:
            return cls(
                version=value.get("version", PROTOCOL_VERSION),
                caller=value.get("caller", ""),
                action=Action(value["action"]),
                request_id=value["request_id"],
                session_id=value["session_id"],
                created_at=value.get("created_at", _timestamp()),
                payload=value.get("payload", {}),
            ).validate()
        except (KeyError, TypeError, ValueError) as exc:
            raise IPCError(f"invalid request envelope: {exc}") from exc


@dataclass(frozen=True)
class ResponseEnvelope:
    request_id: str
    session_id: str
    ok: bool
    payload: dict[str, Any] = field(default_factory=dict)
    error_code: ErrorCode | None = None
    error_message: str | None = None
    version: int = PROTOCOL_VERSION
    responder: str = "etumax"
    created_at: str = field(default_factory=_timestamp)

    def validate(self) -> "ResponseEnvelope":
        if self.version != PROTOCOL_VERSION:
            raise IPCError(f"unsupported protocol version: {self.version}")
        if self.responder != "etumax":
            raise IPCError("response responder must be etumax")
        request_id = _validate_id("request_id", self.request_id)
        session_id = _validate_id("session_id", self.session_id)
        payload = _validate_payload(self.payload)
        if self.ok:
            if self.error_code is not None or self.error_message is not None:
                raise IPCError("successful response cannot contain an error")
        else:
            if self.error_code is None or not self.error_message:
                raise IPCError("failed response requires error_code and error_message")
        return ResponseEnvelope(
            request_id=request_id,
            session_id=session_id,
            ok=bool(self.ok),
            payload=payload,
            error_code=ErrorCode(self.error_code) if self.error_code is not None else None,
            error_message=self.error_message,
            version=self.version,
            responder=self.responder,
            created_at=self.created_at,
        )

    def to_json(self) -> str:
        value = asdict(self.validate())
        if self.error_code is not None:
            value["error_code"] = self.error_code.value
        return json.dumps(value, separators=(",", ":"), sort_keys=True)

    @classmethod
    def from_json(cls, raw: str) -> "ResponseEnvelope":
        try:
            value = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise IPCError(f"invalid JSON: {exc}") from exc
        if not isinstance(value, dict):
            raise IPCError("response root must be an object")
        error_code = value.get("error_code")
        try:
            return cls(
                version=value.get("version", PROTOCOL_VERSION),
                responder=value.get("responder", ""),
                request_id=value["request_id"],
                session_id=value["session_id"],
                ok=value["ok"],
                created_at=value.get("created_at", _timestamp()),
                payload=value.get("payload", {}),
                error_code=ErrorCode(error_code) if error_code is not None else None,
                error_message=value.get("error_message"),
            ).validate()
        except (KeyError, TypeError, ValueError) as exc:
            raise IPCError(f"invalid response envelope: {exc}") from exc
