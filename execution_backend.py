#!/usr/bin/env python3
"""Generic ETroute execution backend foundation with no PRoot dependency."""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Mapping, Sequence


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


def default_registry() -> ExecutionBackendRegistry:
    return ExecutionBackendRegistry([NativeBackend()])
