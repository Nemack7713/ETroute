#!/usr/bin/env python3
"""ETroute-owned capability, network, and workspace policy primitives.

This module is intentionally Android/rootless oriented. It does not execute
ETumax workloads and does not contain ETumax runtime logic.
"""
from __future__ import annotations

import ipaddress
import json
import os
import shutil
import tempfile
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Iterable


class PolicyError(RuntimeError):
    pass


class Capability(str, Enum):
    ACCESSIBILITY_READ = "accessibility_read"
    ACCESSIBILITY_ACTION = "accessibility_action"
    APP_LAUNCH = "app_launch"
    NOTIFICATIONS = "notifications"
    STORAGE_READ = "storage_read"
    STORAGE_WRITE = "storage_write"
    LOCAL_NETWORK = "local_network"
    EXTERNAL_NETWORK = "external_network"


class NetworkMode(str, Enum):
    OFFLINE = "offline"
    LOOPBACK_ONLY = "loopback_only"
    LOCAL_LAN = "local_lan"
    ALLOWLIST = "allowlist"
    UNRESTRICTED = "unrestricted"


@dataclass(frozen=True)
class Endpoint:
    host: str
    port: int

    def normalized(self) -> "Endpoint":
        host = self.host.strip().lower()
        if not host:
            raise PolicyError("network host cannot be empty")
        if not 1 <= self.port <= 65535:
            raise PolicyError("network port must be between 1 and 65535")
        return Endpoint(host, self.port)

    def key(self) -> str:
        value = self.normalized()
        return f"{value.host}:{value.port}"


@dataclass(frozen=True)
class TaskPolicy:
    task_id: str
    capabilities: frozenset[Capability] = field(default_factory=frozenset)
    allowed_roots: tuple[Path, ...] = ()
    network_mode: NetworkMode = NetworkMode.OFFLINE
    allowed_endpoints: frozenset[str] = field(default_factory=frozenset)
    timeout_seconds: int = 300
    max_output_bytes: int = 1_048_576

    def validate(self) -> "TaskPolicy":
        task_id = self.task_id.strip()
        if not task_id or any(part in task_id for part in ("/", "\\", "..")):
            raise PolicyError("task_id must be a simple traversal-free identifier")
        if self.timeout_seconds <= 0:
            raise PolicyError("timeout_seconds must be positive")
        if self.max_output_bytes <= 0:
            raise PolicyError("max_output_bytes must be positive")
        roots = tuple(root.expanduser().resolve() for root in self.allowed_roots)
        endpoints = frozenset(Endpoint(*_split_endpoint(item)).key() for item in self.allowed_endpoints)
        return TaskPolicy(
            task_id=task_id,
            capabilities=frozenset(self.capabilities),
            allowed_roots=roots,
            network_mode=self.network_mode,
            allowed_endpoints=endpoints,
            timeout_seconds=self.timeout_seconds,
            max_output_bytes=self.max_output_bytes,
        )

    def require(self, capability: Capability) -> None:
        if capability not in self.capabilities:
            raise PolicyError(f"task {self.task_id!r} lacks capability {capability.value!r}")

    def authorize_path(self, path: Path, *, write: bool = False) -> Path:
        self.require(Capability.STORAGE_WRITE if write else Capability.STORAGE_READ)
        resolved = path.expanduser().resolve()
        for root in self.validate().allowed_roots:
            if resolved == root or root in resolved.parents:
                return resolved
        raise PolicyError(f"path is outside task roots: {resolved}")

    def authorize_endpoint(self, endpoint: Endpoint) -> Endpoint:
        value = endpoint.normalized()
        mode = self.network_mode
        if mode is NetworkMode.OFFLINE:
            raise PolicyError("network access is disabled")
        if mode is NetworkMode.LOOPBACK_ONLY:
            if not _is_loopback(value.host):
                raise PolicyError("only loopback endpoints are permitted")
            self.require(Capability.LOCAL_NETWORK)
        elif mode is NetworkMode.LOCAL_LAN:
            if not (_is_loopback(value.host) or _is_private(value.host)):
                raise PolicyError("only loopback or private-LAN endpoints are permitted")
            self.require(Capability.LOCAL_NETWORK)
        elif mode is NetworkMode.ALLOWLIST:
            if value.key() not in self.allowed_endpoints:
                raise PolicyError(f"endpoint is not allowlisted: {value.key()}")
            self.require(Capability.LOCAL_NETWORK if _is_loopback(value.host) or _is_private(value.host) else Capability.EXTERNAL_NETWORK)
        else:
            self.require(Capability.LOCAL_NETWORK if _is_loopback(value.host) or _is_private(value.host) else Capability.EXTERNAL_NETWORK)
        return value


@dataclass(frozen=True)
class JournalEntry:
    operation: str
    target: str
    backup: str | None
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())


class StagedWorkspace:
    """Copy-on-write style project staging without root or OverlayFS."""

    def __init__(self, source: Path, staging_parent: Path) -> None:
        self.source = source.expanduser().resolve()
        self.staging_parent = staging_parent.expanduser().resolve()
        self.workspace: Path | None = None
        self.entries: list[JournalEntry] = []

    def create(self) -> Path:
        if not self.source.is_dir():
            raise PolicyError(f"workspace source is not a directory: {self.source}")
        self.staging_parent.mkdir(parents=True, exist_ok=True)
        self.workspace = Path(tempfile.mkdtemp(prefix=f".{self.source.name}.stage-", dir=self.staging_parent))
        shutil.copytree(self.source, self.workspace, dirs_exist_ok=True)
        return self.workspace

    def record_replace(self, relative_path: str) -> None:
        workspace = self._require_workspace()
        target = _safe_relative(relative_path)
        original = self.source / target
        backup = workspace / ".etroute-journal" / target
        backup.parent.mkdir(parents=True, exist_ok=True)
        if original.is_file():
            shutil.copy2(original, backup)
            backup_value: str | None = str(backup.relative_to(workspace))
        else:
            backup_value = None
        self.entries.append(JournalEntry("replace", str(target), backup_value))
        self._write_journal()

    def promote(self, destination: Path) -> Path:
        workspace = self._require_workspace()
        destination = destination.expanduser().resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        replacement = destination.with_name(f".{destination.name}.new-{os.getpid()}")
        if replacement.exists():
            shutil.rmtree(replacement)
        shutil.copytree(workspace, replacement, ignore=shutil.ignore_patterns(".etroute-journal"))
        previous = destination.with_name(f".{destination.name}.previous-{os.getpid()}")
        if destination.exists():
            os.replace(destination, previous)
        try:
            os.replace(replacement, destination)
        except OSError:
            if previous.exists() and not destination.exists():
                os.replace(previous, destination)
            raise
        else:
            if previous.exists():
                shutil.rmtree(previous)
        return destination

    def discard(self) -> None:
        if self.workspace and self.workspace.exists():
            shutil.rmtree(self.workspace)
        self.workspace = None

    def _require_workspace(self) -> Path:
        if self.workspace is None or not self.workspace.is_dir():
            raise PolicyError("staged workspace has not been created")
        return self.workspace

    def _write_journal(self) -> None:
        workspace = self._require_workspace()
        path = workspace / ".etroute-journal" / "journal.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        temp = path.with_suffix(".tmp")
        temp.write_text(json.dumps([asdict(entry) for entry in self.entries], indent=2) + "\n", encoding="utf-8")
        os.replace(temp, path)


def _safe_relative(value: str) -> Path:
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise PolicyError("workspace path must be relative and traversal-free")
    return path


def _split_endpoint(value: str) -> tuple[str, int]:
    host, separator, port = value.rpartition(":")
    if not separator:
        raise PolicyError(f"endpoint must use HOST:PORT: {value!r}")
    try:
        return host, int(port)
    except ValueError as exc:
        raise PolicyError(f"invalid endpoint port: {value!r}") from exc


def _ip(value: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    try:
        return ipaddress.ip_address(value)
    except ValueError:
        return None


def _is_loopback(host: str) -> bool:
    return host in {"localhost", "::1"} or bool((address := _ip(host)) and address.is_loopback)


def _is_private(host: str) -> bool:
    return bool((address := _ip(host)) and address.is_private)
