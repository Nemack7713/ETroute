#!/usr/bin/env python3
"""ETroute Event Protocol v1.

Stdlib-only protocol objects shared by the ETroute/ETumax boundary. This module
contains no transport, Android, trading, or PRoot logic. It defines the stable
wire contract used to describe events and acknowledgements.
"""
from __future__ import annotations

import json
import time
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from enum import IntEnum
from typing import Any, Mapping

SCHEMA_VERSION = 1
MAX_EVENT_TYPE_LENGTH = 128
MAX_SOURCE_LENGTH = 64
MAX_DEDUPE_KEY_LENGTH = 256
MAX_PAYLOAD_JSON_BYTES = 64 * 1024


class ProtocolError(ValueError):
    """Raised when an event or acknowledgement violates protocol v1."""


class Severity(IntEnum):
    CRITICAL = 0
    URGENT = 1
    WARNING = 2
    INFO = 3
    DEBUG = 4

    @classmethod
    def parse(cls, value: str | "Severity") -> "Severity":
        if isinstance(value, cls):
            return value
        normalized = str(value).strip().upper()
        try:
            return cls[normalized]
        except KeyError as exc:
            raise ProtocolError(f"unsupported severity: {value!r}") from exc

    @property
    def wire_value(self) -> str:
        return self.name.lower()


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _require_text(name: str, value: str, *, maximum: int) -> str:
    cleaned = str(value).strip()
    if not cleaned:
        raise ProtocolError(f"{name} cannot be empty")
    if len(cleaned) > maximum:
        raise ProtocolError(f"{name} exceeds {maximum} characters")
    return cleaned


def _validate_payload(payload: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise ProtocolError("payload must be a JSON object")
    copied = dict(payload)
    try:
        encoded = json.dumps(copied, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise ProtocolError(f"payload is not JSON serializable: {exc}") from exc
    if len(encoded) > MAX_PAYLOAD_JSON_BYTES:
        raise ProtocolError(
            f"payload exceeds {MAX_PAYLOAD_JSON_BYTES} encoded bytes"
        )
    return copied


@dataclass(frozen=True)
class EventEnvelope:
    """Stable ETroute/ETumax event wire contract."""

    event_type: str
    source: str
    payload: Mapping[str, Any]
    severity: Severity | str = Severity.INFO
    event_id: str = field(default_factory=lambda: f"evt-{uuid.uuid4().hex}")
    request_id: str | None = None
    session_id: str | None = None
    created_at: str = field(default_factory=utc_now_iso)
    ttl_seconds: int | None = None
    dedupe_key: str | None = None
    schema_version: int = SCHEMA_VERSION

    def __post_init__(self) -> None:
        if self.schema_version != SCHEMA_VERSION:
            raise ProtocolError(
                f"unsupported event schema_version: {self.schema_version}"
            )
        object.__setattr__(
            self,
            "event_type",
            _require_text("event_type", self.event_type, maximum=MAX_EVENT_TYPE_LENGTH),
        )
        object.__setattr__(
            self,
            "source",
            _require_text("source", self.source, maximum=MAX_SOURCE_LENGTH),
        )
        object.__setattr__(self, "severity", Severity.parse(self.severity))
        object.__setattr__(self, "event_id", _require_text("event_id", self.event_id, maximum=128))
        object.__setattr__(self, "payload", _validate_payload(self.payload))

        if self.request_id is not None:
            object.__setattr__(
                self,
                "request_id",
                _require_text("request_id", self.request_id, maximum=128),
            )
        if self.session_id is not None:
            object.__setattr__(
                self,
                "session_id",
                _require_text("session_id", self.session_id, maximum=128),
            )
        if self.ttl_seconds is not None:
            if isinstance(self.ttl_seconds, bool) or not isinstance(self.ttl_seconds, int):
                raise ProtocolError("ttl_seconds must be an integer")
            if self.ttl_seconds <= 0:
                raise ProtocolError("ttl_seconds must be greater than zero")
        if self.dedupe_key is not None:
            object.__setattr__(
                self,
                "dedupe_key",
                _require_text(
                    "dedupe_key",
                    self.dedupe_key,
                    maximum=MAX_DEDUPE_KEY_LENGTH,
                ),
            )
        try:
            datetime.fromisoformat(self.created_at)
        except ValueError as exc:
            raise ProtocolError("created_at must be ISO-8601") from exc

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["type"] = result.pop("event_type")
        result["severity"] = self.severity.wire_value
        return result

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), separators=(",", ":"), sort_keys=True)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "EventEnvelope":
        if not isinstance(value, Mapping):
            raise ProtocolError("event JSON root must be an object")
        data = dict(value)
        event_type = data.pop("type", None)
        if event_type is None:
            raise ProtocolError("event field 'type' is required")
        allowed = {
            "schema_version", "event_id", "request_id", "session_id", "source",
            "severity", "created_at", "ttl_seconds", "dedupe_key", "payload",
        }
        unknown = sorted(set(data) - allowed)
        if unknown:
            raise ProtocolError(f"unknown event fields: {', '.join(unknown)}")
        return cls(event_type=event_type, **data)

    @classmethod
    def from_json(cls, text: str) -> "EventEnvelope":
        try:
            value = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ProtocolError(f"invalid event JSON: {exc}") from exc
        return cls.from_dict(value)

    def expires_at_epoch(self) -> float | None:
        if self.ttl_seconds is None:
            return None
        created = datetime.fromisoformat(self.created_at)
        if created.tzinfo is None:
            created = created.replace(tzinfo=timezone.utc)
        return created.timestamp() + self.ttl_seconds

    def is_expired(self, *, now_epoch: float | None = None) -> bool:
        expires = self.expires_at_epoch()
        if expires is None:
            return False
        return (time.time() if now_epoch is None else now_epoch) >= expires


@dataclass(frozen=True)
class EventAcknowledgement:
    """Acknowledges validation/acceptance separately from downstream delivery."""

    event_id: str
    accepted: bool
    route: str | None = None
    status: str = "accepted"
    message: str | None = None
    acknowledged_at: str = field(default_factory=utc_now_iso)
    schema_version: int = SCHEMA_VERSION

    def __post_init__(self) -> None:
        if self.schema_version != SCHEMA_VERSION:
            raise ProtocolError(
                f"unsupported acknowledgement schema_version: {self.schema_version}"
            )
        object.__setattr__(self, "event_id", _require_text("event_id", self.event_id, maximum=128))
        object.__setattr__(self, "status", _require_text("status", self.status, maximum=64))
        if self.route is not None:
            object.__setattr__(self, "route", _require_text("route", self.route, maximum=128))
        if self.message is not None:
            object.__setattr__(self, "message", str(self.message).strip() or None)
        try:
            datetime.fromisoformat(self.acknowledged_at)
        except ValueError as exc:
            raise ProtocolError("acknowledged_at must be ISO-8601") from exc

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), separators=(",", ":"), sort_keys=True)
