#!/usr/bin/env python3
"""Canonical ETroute PRoot launch specification.

This module is the source of truth for rootfs, bind, root-id, kernel-release,
kill-on-exit, guest working directory, and guest command semantics. It contains
no subprocess execution logic so the same wire contract can be implemented by
Android or other ETroute hosts.
"""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Mapping, Sequence

PROOT_LAUNCH_SCHEMA_VERSION = 1


class ProotLaunchSpecError(ValueError):
    pass


@dataclass(frozen=True)
class LaunchBind:
    host: str
    guest: str
    no_dereference: bool = False

    def validate(self) -> "LaunchBind":
        host = Path(self.host).expanduser().resolve()
        if not host.exists():
            raise ProotLaunchSpecError(f"bind source does not exist: {host}")

        guest = PurePosixPath(self.guest)
        if not guest.is_absolute() or ".." in guest.parts:
            raise ProotLaunchSpecError(
                f"guest bind destination must be absolute and traversal-free: {self.guest}"
            )

        return LaunchBind(
            host=str(host),
            guest=str(guest),
            no_dereference=self.no_dereference,
        )

    def serialize(self) -> str:
        validated = self.validate()
        suffix = "!" if validated.no_dereference else ""
        return f"{validated.host}:{validated.guest}{suffix}"


@dataclass(frozen=True)
class ProotLaunchSpec:
    rootfs: str
    command: tuple[str, ...]
    binds: tuple[LaunchBind, ...] = ()
    root_id: bool = True
    kill_on_exit: bool = True
    kernel_release: str | None = None
    guest_working_directory: str | None = None
    environment: Mapping[str, str] = field(default_factory=dict)
    schema_version: int = PROOT_LAUNCH_SCHEMA_VERSION

    def validate(self) -> "ProotLaunchSpec":
        if self.schema_version != PROOT_LAUNCH_SCHEMA_VERSION:
            raise ProotLaunchSpecError(
                f"unsupported PRoot launch schema: {self.schema_version}"
            )

        rootfs = Path(self.rootfs).expanduser().resolve()
        if not rootfs.is_dir():
            raise ProotLaunchSpecError(f"rootfs does not exist: {rootfs}")
        if not any(
            (rootfs / candidate).is_file()
            for candidate in ("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash")
        ):
            raise ProotLaunchSpecError(f"rootfs has no usable shell: {rootfs}")

        if not self.command or not str(self.command[0]).strip():
            raise ProotLaunchSpecError("guest command cannot be empty")

        guest_cwd = self.guest_working_directory
        if guest_cwd is not None:
            parsed = PurePosixPath(guest_cwd)
            if not parsed.is_absolute() or ".." in parsed.parts:
                raise ProotLaunchSpecError(
                    "guest working directory must be absolute and traversal-free"
                )

        seen_guests: set[str] = set()
        normalized_binds: list[LaunchBind] = []
        for bind in self.binds:
            normalized = bind.validate()
            if normalized.guest in seen_guests:
                raise ProotLaunchSpecError(
                    f"duplicate guest bind target: {normalized.guest}"
                )
            seen_guests.add(normalized.guest)
            normalized_binds.append(normalized)

        normalized_env: dict[str, str] = {}
        for key, value in self.environment.items():
            key = str(key).strip()
            if not key or "=" in key or "\x00" in key:
                raise ProotLaunchSpecError(f"invalid environment key: {key!r}")
            normalized_env[key] = str(value)

        return ProotLaunchSpec(
            rootfs=str(rootfs),
            command=tuple(str(part) for part in self.command),
            binds=tuple(normalized_binds),
            root_id=bool(self.root_id),
            kill_on_exit=bool(self.kill_on_exit),
            kernel_release=(
                str(self.kernel_release).strip() if self.kernel_release else None
            ),
            guest_working_directory=guest_cwd,
            environment=normalized_env,
            schema_version=PROOT_LAUNCH_SCHEMA_VERSION,
        )

    def to_dict(self) -> dict[str, Any]:
        validated = self.validate()
        result = asdict(validated)
        result["command"] = list(validated.command)
        result["binds"] = [asdict(bind) for bind in validated.binds]
        result["environment"] = dict(validated.environment)
        return result

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), separators=(",", ":"), sort_keys=True)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ProotLaunchSpec":
        data = dict(value)
        allowed = {
            "schema_version",
            "rootfs",
            "command",
            "binds",
            "root_id",
            "kill_on_exit",
            "kernel_release",
            "guest_working_directory",
            "environment",
        }
        unknown = sorted(set(data) - allowed)
        if unknown:
            raise ProotLaunchSpecError(
                f"unknown PRoot launch fields: {', '.join(unknown)}"
            )
        if "rootfs" not in data or "command" not in data:
            raise ProotLaunchSpecError("rootfs and command are required")
        data["command"] = tuple(data.get("command") or ())
        data["binds"] = tuple(LaunchBind(**bind) for bind in data.get("binds", ()))
        return cls(**data).validate()

    @classmethod
    def from_json(cls, text: str) -> "ProotLaunchSpec":
        try:
            value = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ProotLaunchSpecError(f"invalid PRoot launch JSON: {exc}") from exc
        if not isinstance(value, dict):
            raise ProotLaunchSpecError("PRoot launch JSON root must be an object")
        return cls.from_dict(value)


def build_proot_argv(
    proot_binary: str,
    spec: ProotLaunchSpec,
    *,
    supports_kill_on_exit: bool,
) -> list[str]:
    validated = spec.validate()
    argv = [proot_binary, "--rootfs", validated.rootfs]

    if validated.root_id:
        argv.append("--root-id")
    if validated.kernel_release:
        argv.append(f"--kernel-release={validated.kernel_release}")
    if validated.kill_on_exit and supports_kill_on_exit:
        argv.append("--kill-on-exit")

    for bind in validated.binds:
        argv.extend(("--bind", bind.serialize()))

    if validated.guest_working_directory:
        argv.extend(("--cwd", validated.guest_working_directory))

    argv.extend(("--", *validated.command))
    return argv
