#!/usr/bin/env python3
"""Persistent ETroute session lifecycle manager.

Sessions belong to ETroute's control plane. This module tracks lifecycle,
expiration, and capability cleanup without executing ETumax workloads.
"""
from __future__ import annotations

import json
import os
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone
from enum import Enum
from pathlib import Path

from etroute_capability_broker import CapabilityBroker
from etroute_policy import PolicyError


class SessionState(str, Enum):
    CREATED = "created"
    ACTIVE = "active"
    STOPPED = "stopped"
    EXPIRED = "expired"
    FAILED = "failed"


@dataclass(frozen=True)
class SessionRecord:
    session_id: str
    task_id: str
    state: SessionState
    created_at: str
    updated_at: str
    expires_at: str | None = None
    stopped_at: str | None = None
    failure_reason: str | None = None
    metadata: dict[str, str] = field(default_factory=dict)


class SessionManager:
    def __init__(self, state_path: Path, capability_broker: CapabilityBroker) -> None:
        self.state_path = state_path.expanduser().resolve()
        self.capability_broker = capability_broker
        self._sessions: dict[str, SessionRecord] = {}
        self._load()

    def create(
        self,
        task_id: str,
        *,
        ttl_seconds: int | None = 3600,
        metadata: dict[str, str] | None = None,
        session_id: str | None = None,
    ) -> SessionRecord:
        task_id = _validate_token("task_id", task_id)
        if ttl_seconds is not None and ttl_seconds <= 0:
            raise PolicyError("ttl_seconds must be positive or None")
        session_id = _validate_token("session_id", session_id or uuid.uuid4().hex)
        if session_id in self._sessions:
            raise PolicyError(f"session already exists: {session_id}")
        now = datetime.now(timezone.utc)
        expires_at = None if ttl_seconds is None else (now + timedelta(seconds=ttl_seconds)).isoformat()
        record = SessionRecord(
            session_id=session_id,
            task_id=task_id,
            state=SessionState.CREATED,
            created_at=now.isoformat(),
            updated_at=now.isoformat(),
            expires_at=expires_at,
            metadata=_validate_metadata(metadata or {}),
        )
        self._sessions[session_id] = record
        self._save()
        return record

    def activate(self, session_id: str) -> SessionRecord:
        record = self.get(session_id)
        if self._is_expired(record):
            return self.expire(session_id)
        if record.state not in {SessionState.CREATED, SessionState.ACTIVE}:
            raise PolicyError(f"cannot activate session in state {record.state.value}")
        return self._replace(record, state=SessionState.ACTIVE)

    def stop(self, session_id: str) -> SessionRecord:
        record = self.get(session_id)
        if record.state in {SessionState.STOPPED, SessionState.EXPIRED}:
            return record
        now = datetime.now(timezone.utc).isoformat()
        updated = self._replace(record, state=SessionState.STOPPED, stopped_at=now)
        self.capability_broker.revoke(session_id, reason="session stopped")
        return updated

    def fail(self, session_id: str, reason: str) -> SessionRecord:
        reason = reason.strip()
        if not reason:
            raise PolicyError("failure reason cannot be empty")
        record = self.get(session_id)
        if record.state in {SessionState.STOPPED, SessionState.EXPIRED}:
            raise PolicyError(f"cannot fail session in state {record.state.value}")
        updated = self._replace(record, state=SessionState.FAILED, failure_reason=reason)
        self.capability_broker.revoke(session_id, reason="session failed")
        return updated

    def expire(self, session_id: str) -> SessionRecord:
        record = self.get(session_id)
        if record.state is SessionState.EXPIRED:
            return record
        updated = self._replace(record, state=SessionState.EXPIRED)
        self.capability_broker.revoke(session_id, reason="session expired")
        return updated

    def expire_due(self) -> tuple[SessionRecord, ...]:
        expired: list[SessionRecord] = []
        for record in list(self._sessions.values()):
            if record.state in {SessionState.CREATED, SessionState.ACTIVE} and self._is_expired(record):
                expired.append(self.expire(record.session_id))
        return tuple(expired)

    def get(self, session_id: str) -> SessionRecord:
        session_id = _validate_token("session_id", session_id)
        try:
            return self._sessions[session_id]
        except KeyError as exc:
            raise PolicyError(f"unknown session: {session_id}") from exc

    def list(self, *, include_terminal: bool = True) -> tuple[SessionRecord, ...]:
        records = tuple(self._sessions.values())
        if include_terminal:
            return records
        return tuple(
            record for record in records
            if record.state not in {SessionState.STOPPED, SessionState.EXPIRED, SessionState.FAILED}
        )

    def _replace(self, record: SessionRecord, **changes: object) -> SessionRecord:
        data = asdict(record)
        data.update(changes)
        data["state"] = changes.get("state", record.state)
        data["updated_at"] = datetime.now(timezone.utc).isoformat()
        updated = SessionRecord(**data)
        self._sessions[record.session_id] = updated
        self._save()
        return updated

    def _is_expired(self, record: SessionRecord) -> bool:
        if record.expires_at is None:
            return False
        try:
            expires_at = datetime.fromisoformat(record.expires_at)
        except ValueError as exc:
            raise PolicyError(f"invalid expires_at for session {record.session_id}") from exc
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)
        return datetime.now(timezone.utc) >= expires_at.astimezone(timezone.utc)

    def _load(self) -> None:
        if not self.state_path.exists():
            return
        try:
            raw = json.loads(self.state_path.read_text(encoding="utf-8"))
            if not isinstance(raw, list):
                raise ValueError("session state root must be an array")
            for item in raw:
                record = SessionRecord(
                    session_id=item["session_id"],
                    task_id=item["task_id"],
                    state=SessionState(item["state"]),
                    created_at=item["created_at"],
                    updated_at=item["updated_at"],
                    expires_at=item.get("expires_at"),
                    stopped_at=item.get("stopped_at"),
                    failure_reason=item.get("failure_reason"),
                    metadata=_validate_metadata(item.get("metadata", {})),
                )
                self._sessions[record.session_id] = record
        except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise PolicyError(f"invalid session state {self.state_path}: {exc}") from exc

    def _save(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        payload = []
        for record in self._sessions.values():
            item = asdict(record)
            item["state"] = record.state.value
            payload.append(item)
        temp = self.state_path.with_name(f".{self.state_path.name}.tmp-{os.getpid()}")
        temp.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        os.replace(temp, self.state_path)


def _validate_token(name: str, value: str) -> str:
    if not isinstance(value, str):
        raise PolicyError(f"{name} must be a string")
    normalized = value.strip()
    if not normalized or len(normalized) > 128:
        raise PolicyError(f"{name} must contain 1 to 128 characters")
    if any(character in normalized for character in ("/", "\\", "\n", "\r", "\x00")):
        raise PolicyError(f"{name} contains forbidden characters")
    return normalized


def _validate_metadata(metadata: dict[str, str]) -> dict[str, str]:
    if not isinstance(metadata, dict):
        raise PolicyError("metadata must be an object")
    result: dict[str, str] = {}
    for key, value in metadata.items():
        normalized_key = _validate_token("metadata key", key)
        if not isinstance(value, str) or len(value) > 512:
            raise PolicyError("metadata values must be strings up to 512 characters")
        result[normalized_key] = value
    return result
