#!/usr/bin/env python3
"""ETroute execution backends for native and PRoot-rootfs execution."""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path, PurePosixPath
from typing import Mapping, Sequence

from proot_launch_spec import LaunchBind, ProotLaunchSpec, build_proot_argv


class ExecutionState(str, Enum):
    STARTING = "starting"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    STOPPED = "stopped"


@dataclass(frozen=True)
class ExecutionRequest:
    executable: str
    arguments: tuple[str, ...] = ()
    working_directory: Path | None = None
    environment: Mapping[str, str] = field(default_factory=dict)

    def validate(self) -> "ExecutionRequest":
        if not self.executable.strip():
            raise ValueError("executable cannot be blank")
        if self.working_directory is not None and not self.working_directory.is_dir():
            raise ValueError(f"working directory does not exist: {self.working_directory}")
        return self


@dataclass(frozen=True)
class BackendCapabilities:
    available: bool
    architecture: str
    interactive: bool = False
    gpu: bool = False
    vulkan: bool = False
    details: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class ExecutionResult:
    execution_id: str
    exit_code: int | None
    stdout: str
    stderr: str
    state: ExecutionState


@dataclass(frozen=True)
class RootfsBind:
    host: Path
    guest: str
    no_dereference: bool = False

    def validate(self) -> "RootfsBind":
        host = self.host.expanduser().resolve()
        if not host.exists():
            raise ValueError(f"bind source does not exist: {host}")

        guest = PurePosixPath(self.guest)
        if not guest.is_absolute() or ".." in guest.parts:
            raise ValueError(
                f"guest bind destination must be absolute and traversal-free: {self.guest}"
            )

        return RootfsBind(
            host=host,
            guest=str(guest),
            no_dereference=self.no_dereference,
        )

    def serialize(self) -> str:
        validated = self.validate()
        suffix = "!" if validated.no_dereference else ""
        return f"{validated.host}:{validated.guest}{suffix}"

    def to_launch_bind(self) -> LaunchBind:
        validated = self.validate()
        return LaunchBind(
            host=str(validated.host),
            guest=validated.guest,
            no_dereference=validated.no_dereference,
        )


@dataclass(frozen=True)
class ProotConfig:
    rootfs: Path
    proot_binary: str = "proot"
    binds: tuple[RootfsBind, ...] = ()
    root_id: bool = True
    kill_on_exit: bool = True
    kernel_release: str | None = None
    guest_working_directory: str | None = None

    def validate(self) -> "ProotConfig":
        rootfs = self.rootfs.expanduser().resolve()
        if not rootfs.is_dir():
            raise ValueError(f"rootfs does not exist: {rootfs}")

        if not any(
            (rootfs / candidate).is_file()
            for candidate in ("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash")
        ):
            raise ValueError(f"rootfs has no usable shell: {rootfs}")

        if self.guest_working_directory is not None:
            guest_cwd = PurePosixPath(self.guest_working_directory)
            if not guest_cwd.is_absolute() or ".." in guest_cwd.parts:
                raise ValueError(
                    "guest working directory must be absolute and traversal-free"
                )

        for bind in self.binds:
            bind.validate()

        return ProotConfig(
            rootfs=rootfs,
            proot_binary=self.proot_binary,
            binds=self.binds,
            root_id=self.root_id,
            kill_on_exit=self.kill_on_exit,
            kernel_release=self.kernel_release,
            guest_working_directory=self.guest_working_directory,
        )

    def to_launch_spec(self, request: ExecutionRequest) -> ProotLaunchSpec:
        config = self.validate()
        request = request.validate()
        return ProotLaunchSpec(
            rootfs=str(config.rootfs),
            command=(request.executable, *request.arguments),
            binds=tuple(bind.to_launch_bind() for bind in config.binds),
            root_id=config.root_id,
            kill_on_exit=config.kill_on_exit,
            kernel_release=config.kernel_release,
            guest_working_directory=config.guest_working_directory,
            environment=request.environment,
        ).validate()


class ExecutionBackend(ABC):
    backend_id: str
    display_name: str

    @abstractmethod
    def probe(self) -> BackendCapabilities:
        raise NotImplementedError

    @abstractmethod
    def execute(self, request: ExecutionRequest) -> ExecutionResult:
        raise NotImplementedError

    @abstractmethod
    def stop(self, execution_id: str) -> bool:
        raise NotImplementedError


class ExecutionBackendRegistry:
    def __init__(self, backends: Sequence[ExecutionBackend]) -> None:
        self._backends = {backend.backend_id: backend for backend in backends}

    def get(self, backend_id: str) -> ExecutionBackend | None:
        return self._backends.get(backend_id)

    def require(self, backend_id: str) -> ExecutionBackend:
        backend = self.get(backend_id)
        if backend is None:
            raise KeyError(f"backend not registered: {backend_id}")
        return backend

    def available(self) -> list[ExecutionBackend]:
        return [backend for backend in self._backends.values() if backend.probe().available]


class NativeBackend(ExecutionBackend):
    backend_id = "native"
    display_name = "ETroute Native"

    def __init__(self) -> None:
        self._running: dict[str, subprocess.Popen[str]] = {}

    def probe(self) -> BackendCapabilities:
        return BackendCapabilities(
            available=True,
            architecture=platform.machine() or "unknown",
            interactive=True,
            gpu=False,
            vulkan=False,
            details={
                "platform": platform.system() or "unknown",
                "python": platform.python_version(),
            },
        )

    def execute(self, request: ExecutionRequest) -> ExecutionResult:
        request = request.validate()
        executable = self._resolve_executable(request.executable)
        execution_id = str(uuid.uuid4())
        env = os.environ.copy()
        env.update(request.environment)

        try:
            process = subprocess.Popen(
                [executable, *request.arguments],
                cwd=str(request.working_directory) if request.working_directory else None,
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
            self._running[execution_id] = process
            stdout, stderr = process.communicate()
            exit_code = process.returncode
            self._running.pop(execution_id, None)
            return ExecutionResult(
                execution_id=execution_id,
                exit_code=exit_code,
                stdout=stdout,
                stderr=stderr,
                state=ExecutionState.COMPLETED if exit_code == 0 else ExecutionState.FAILED,
            )
        except OSError as exc:
            self._running.pop(execution_id, None)
            return ExecutionResult(
                execution_id=execution_id,
                exit_code=None,
                stdout="",
                stderr=str(exc),
                state=ExecutionState.FAILED,
            )

    def stop(self, execution_id: str) -> bool:
        process = self._running.pop(execution_id, None)
        if process is None:
            return False
        return _terminate_process(process)

    @staticmethod
    def _resolve_executable(executable: str) -> str:
        candidate = Path(executable).expanduser()
        if candidate.is_absolute() or candidate.parent != Path("."):
            if not candidate.is_file():
                raise FileNotFoundError(f"executable not found: {candidate}")
            return str(candidate)

        resolved = shutil.which(executable)
        if resolved is None:
            raise FileNotFoundError(f"executable not found on PATH: {executable}")
        return resolved


class ProotBackend(ExecutionBackend):
    backend_id = "proot"
    display_name = "ETroute PRoot/rootfs"

    def __init__(self, config: ProotConfig) -> None:
        self._config = config.validate()
        self._running: dict[str, subprocess.Popen[str]] = {}
        self._resolved_binary = self._resolve_proot(self._config.proot_binary)
        self._supports_kill_on_exit = self._probe_flag("--kill-on-exit")

    def probe(self) -> BackendCapabilities:
        try:
            config = self._config.validate()
            binary = self._resolve_proot(config.proot_binary)
            version = self._read_version(binary)
            return BackendCapabilities(
                available=True,
                architecture=platform.machine() or "unknown",
                interactive=True,
                gpu=False,
                vulkan=False,
                details={
                    "runtime": "proot",
                    "binary": binary,
                    "version": version,
                    "rootfs": str(config.rootfs),
                    "root_id": str(config.root_id).lower(),
                    "kill_on_exit": str(
                        config.kill_on_exit and self._supports_kill_on_exit
                    ).lower(),
                    "bind_count": str(len(config.binds)),
                    "launch_schema": "1",
                },
            )
        except (OSError, ValueError) as exc:
            return BackendCapabilities(
                available=False,
                architecture=platform.machine() or "unknown",
                interactive=False,
                details={"error": str(exc)},
            )

    def execute(self, request: ExecutionRequest) -> ExecutionResult:
        request = request.validate()
        execution_id = str(uuid.uuid4())
        env = os.environ.copy()
        env.update(request.environment)
        argv = self._build_argv(request)

        try:
            process = subprocess.Popen(
                argv,
                cwd=str(request.working_directory) if request.working_directory else None,
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                start_new_session=True,
            )
            self._running[execution_id] = process
            stdout, stderr = process.communicate()
            exit_code = process.returncode
            self._running.pop(execution_id, None)
            return ExecutionResult(
                execution_id=execution_id,
                exit_code=exit_code,
                stdout=stdout,
                stderr=stderr,
                state=ExecutionState.COMPLETED if exit_code == 0 else ExecutionState.FAILED,
            )
        except OSError as exc:
            self._running.pop(execution_id, None)
            return ExecutionResult(
                execution_id=execution_id,
                exit_code=None,
                stdout="",
                stderr=str(exc),
                state=ExecutionState.FAILED,
            )

    def stop(self, execution_id: str) -> bool:
        process = self._running.pop(execution_id, None)
        if process is None:
            return False
        return _terminate_process(process)

    def _build_argv(self, request: ExecutionRequest) -> list[str]:
        spec = self._config.to_launch_spec(request)
        return build_proot_argv(
            self._resolved_binary,
            spec,
            supports_kill_on_exit=self._supports_kill_on_exit,
        )

    def _probe_flag(self, flag: str) -> bool:
        try:
            result = subprocess.run(
                [self._resolved_binary, "--help"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=10,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            return False
        return flag in result.stdout

    @staticmethod
    def _resolve_proot(binary: str) -> str:
        candidate = Path(binary).expanduser()
        if candidate.is_absolute() or candidate.parent != Path("."):
            if not candidate.is_file():
                raise FileNotFoundError(f"PRoot executable not found: {candidate}")
            return str(candidate)

        resolved = shutil.which(binary)
        if resolved is None:
            raise FileNotFoundError(f"PRoot executable not found on PATH: {binary}")
        return resolved

    @staticmethod
    def _read_version(binary: str) -> str:
        for flag in ("--version", "-V"):
            try:
                result = subprocess.run(
                    [binary, flag],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    timeout=10,
                    check=False,
                )
            except (OSError, subprocess.TimeoutExpired):
                continue
            output = result.stdout.strip()
            if output:
                return output.splitlines()[0]
        return "unknown"


def _terminate_process(process: subprocess.Popen[str]) -> bool:
    try:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
        return True
    except OSError:
        return False


def default_registry(
    proot_config: ProotConfig | None = None,
) -> ExecutionBackendRegistry:
    backends: list[ExecutionBackend] = [NativeBackend()]
    if proot_config is not None:
        backends.insert(0, ProotBackend(proot_config))
    return ExecutionBackendRegistry(backends)
