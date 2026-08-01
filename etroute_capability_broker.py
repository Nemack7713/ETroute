#!/usr/bin/env python3
"""Persistent, session-scoped capability broker for ETroute.

This module authorizes ETroute actions and records decisions. It does not
execute ETumax workloads or manage the ETumax runtime.
"""
from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

from etroute_policy import Capability, PolicyError


@dataclass(frozen=True)
class CapabilityGrant:
    session_id: str
    capability: Capability
    granted_by: str
    granted_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    revoked_at: str | None = None
    reason: str | None = None

    @property
    def active(self) -> bool:
        return self.revoked_at is None


@dataclass(frozen=True)
class AuditEvent:
    session_id: str
    capability: Capability
    decision: str
    reason: str
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())


class CapabilityBroker:
    def __init__(self, state_path: Path, audit_path: Path | None = None) -> None:
        self.state_path = state_path.expanduser().resolve()
        self.audit_path = (audit_path or self.state_path.with_name("capability-audit.jsonl")).expanduser().resolve()
        self._grants: list[CapabilityGrant] = []
        self._load()

    def grant(
        self,
        session_id: str,
        capabilities: Iterable[Capability],
        *,
        granted_by: str,
        reason: str | None = None,
    ) -> None:
        session_id = _validate_token("session_id", session_id)
        granted_by = _validate_token("granted_by", granted_by)
        requested = {Capability(item) for item in capabilities}
        for capability in requested:
            if self.is_granted(session_id, capability):
                continue
            self._grants.append(
                CapabilityGrant(
                    session_id=session_id,
                    capability=capability,
                    granted_by=granted_by,
                    reason=reason,
                )
            )
            self._audit(session_id, capability, "GRANT", reason or "capability granted")
        self._save()

    def revoke(
        self,
        session_id: str,
        capabilities: Iterable[Capability] | None = None,
        *,
        reason: str = "capability revoked",
    ) -> int:
        session_id = _validate_token("session_id", session_id)
        selected = None if capabilities is None else {Capability(item) for item in capabilities}
        changed = 0
        updated: list[CapabilityGrant] = []
        revoked_at = datetime.now(timezone.utc).isoformat()
        for grant in self._grants:
            should_revoke = (
                grant.session_id == session_id
                and grant.active
                and (selected is None or grant.capability in selected)
            )
            if should_revoke:
                grant = CapabilityGrant(
                    session_id=grant.session_id,
                    capability=grant.capability,
                    granted_by=grant.granted_by,
                    granted_at=grant.granted_at,
                    revoked_at=revoked_at,
                    reason=reason,
                )
                self._audit(session_id, grant.capability, "REVOKE", reason)
                changed += 1
            updated.append(grant)
        self._grants = updated
        if changed:
            self._save()
        return changed

    def is_granted(self, session_id: str, capability: Capability) -> bool:
        session_id = _validate_token("session_id", session_id)
        capability = Capability(capability)
        return any(
            grant.session_id == session_id
            and grant.capability is capability
            and grant.active
            for grant in self._grants
        )

    def require(self, session_id: str, capability: Capability) -> None:
        capability = Capability(capability)
        if not self.is_granted(session_id, capability):
            self._audit(session_id, capability, "DENY", "capability not granted")
            raise PolicyError(
                f"session {session_id!r} lacks capability {capability.value!r}"
            )
        self._audit(session_id, capability, "ALLOW", "capability granted")

    def active_capabilities(self, session_id: str) -> frozenset[Capability]:
        session_id = _validate_token("session_id", session_id)
        return frozenset(
            grant.capability
            for grant in self._grants
            if grant.session_id == session_id and grant.active
        )

    def grants_for(self, session_id: str) -> tuple[CapabilityGrant, ...]:
        session_id = _validate_token("session_id", session_id)
        return tuple(grant for grant in self._grants if grant.session_id == session_id)

    def _load(self) -> None:
        if not self.state_path.exists():
            return
        try:
            raw = json.loads(self.state_path.read_text(encoding="utf-8"))
            if not isinstance(raw, list):
                raise ValueError("capability state root must be an array")
            self._grants = [
                CapabilityGrant(
                    session_id=item["session_id"],
                    capability=Capability(item["capability"]),
                    granted_by=item["granted_by"],
                    granted_at=item["granted_at"],
                    revoked_at=item.get("revoked_at"),
                    reason=item.get("reason"),
                )
                for item in raw
            ]
        except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise PolicyError(f"invalid capability state {self.state_path}: {exc}") from exc

    def _save(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        payload = []
        for grant in self._grants:
            item = asdict(grant)
            item["capability"] = grant.capability.value
            payload.append(item)
        temp = self.state_path.with_name(f".{self.state_path.name}.tmp-{os.getpid()}")
        temp.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        os.replace(temp, self.state_path)

    def _audit(self, session_id: str, capability: Capability, decision: str, reason: str) -> None:
        event = AuditEvent(
            session_id=_validate_token("session_id", session_id),
            capability=Capability(capability),
            decision=decision,
            reason=reason,
        )
        self.audit_path.parent.mkdir(parents=True, exist_ok=True)
        item = asdict(event)
        item["capability"] = event.capability.value
        with self.audit_path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(item, sort_keys=True) + "\n")


def _validate_token(name: str, value: str) -> str:
    if not isinstance(value, str):
        raise PolicyError(f"{name} must be a string")
    normalized = value.strip()
    if not normalized or len(normalized) > 128:
        raise PolicyError(f"{name} must contain 1 to 128 characters")
    if any(character in normalized for character in ("/", "\\", "\n", "\r", "\x00")):
        raise PolicyError(f"{name} contains forbidden characters")
    return normalized
