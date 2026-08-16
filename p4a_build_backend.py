"""Controlled BUILD -> VERIFY backend for ETroute python-for-android work.

The backend separates command construction, execution, artifact discovery and
verification so callers can dry-run safely and tests do not require an Android
SDK/NDK installation.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import time
import zipfile
from dataclasses import asdict, dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Sequence

from etroute_p4a import CapabilityResult, P4aRecipeClassifier, P4aRecipePlanner


class BuildStatus(str, Enum):
    PLANNED = "planned"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    TOOL_UNAVAILABLE = "tool_unavailable"


class VerificationStatus(str, Enum):
    VERIFIED = "verified"
    FAILED = "failed"
    NOT_RUN = "not_run"


@dataclass(frozen=True)
class BuildArtifact:
    path: str
    kind: str
    size: int
    sha256: str


@dataclass
class P4aBuildRequest:
    project: str
    package: str | None = None
    app_name: str = "ETrouteProbe"
    package_id: str = "org.etroute.probe"
    version: str = "0.1.0"
    bootstrap: str = "webview"
    requirements: list[str] = field(default_factory=lambda: ["python3"])
    abis: list[str] = field(default_factory=lambda: ["arm64-v8a"])
    recipe_dirs: list[str] = field(default_factory=list)
    output_dir: str = "dist"
    build_mode: str = "debug"
    artifact_kind: str = "apk"
    timeout_seconds: int = 3600


@dataclass
class P4aBuildResult:
    status: BuildStatus
    command: list[str]
    returncode: int | None
    duration_seconds: float
    stdout_tail: str
    stderr_tail: str
    artifacts: list[BuildArtifact]
    verification: VerificationStatus
    verification_details: list[str]


class P4aBuildBackend:
    BACKEND_NAME = "p4a_build_backend"
    SUPPORTED_ARTIFACTS = {"apk", "aab", "aar"}
    SUPPORTED_MODES = {"debug", "release"}

    def __init__(self, runner: Callable[..., subprocess.CompletedProcess[str]] | None = None) -> None:
        self.runner = runner or subprocess.run
        self.classifier = P4aRecipeClassifier()
        self.planner = P4aRecipePlanner()

    def resolve(self, capability: str, payload: dict[str, Any]) -> CapabilityResult:
        if capability == "p4a.build.plan":
            request = self._request(payload)
            return CapabilityResult(capability, True, self.BACKEND_NAME, self.plan(request))
        if capability == "p4a.build":
            request = self._request(payload)
            result = self.build(request)
            return CapabilityResult(capability, result.status == BuildStatus.SUCCEEDED, self.BACKEND_NAME, self._result_dict(result))
        if capability == "p4a.verify":
            artifacts = [Path(p) for p in payload.get("artifacts", [])]
            status, details = self.verify_artifacts(artifacts, payload.get("abis", ["arm64-v8a"]))
            return CapabilityResult(capability, status == VerificationStatus.VERIFIED, self.BACKEND_NAME, {"status": status.value, "details": details})
        return CapabilityResult(capability, False, self.BACKEND_NAME, {"error": "unsupported_capability"})

    def plan(self, request: P4aBuildRequest) -> dict[str, Any]:
        self._validate(request)
        inspection = self.classifier.inspect(request.project, request.package)
        recipe_plan = self.planner.plan(inspection, request.abis)
        return {
            "request": asdict(request),
            "recipe_plan": {
                "package": recipe_plan.package,
                "package_kind": recipe_plan.package_kind.value,
                "strategy": recipe_plan.strategy.value,
                "recipe_required": recipe_plan.recipe_required,
            },
            "tool": shutil.which("p4a"),
            "command": self.build_command(request),
        }

    def build(self, request: P4aBuildRequest) -> P4aBuildResult:
        self._validate(request)
        command = self.build_command(request)
        if shutil.which("p4a") is None:
            return P4aBuildResult(BuildStatus.TOOL_UNAVAILABLE, command, None, 0.0, "", "p4a executable not found on PATH", [], VerificationStatus.NOT_RUN, ["Install/configure python-for-android on the build host before executing."])

        project = Path(request.project).resolve()
        before = self._artifact_snapshot(project, request.output_dir)
        started = time.monotonic()
        try:
            completed = self.runner(
                command,
                cwd=str(project),
                text=True,
                capture_output=True,
                timeout=request.timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            return P4aBuildResult(BuildStatus.FAILED, command, None, time.monotonic() - started, self._tail(exc.stdout or ""), self._tail(exc.stderr or "") + "\nbuild timed out", [], VerificationStatus.NOT_RUN, ["Build exceeded timeout."])

        duration = time.monotonic() - started
        artifacts = self._discover_new_artifacts(project, request.output_dir, before)
        if completed.returncode != 0:
            return P4aBuildResult(BuildStatus.FAILED, command, completed.returncode, duration, self._tail(completed.stdout), self._tail(completed.stderr), artifacts, VerificationStatus.NOT_RUN, ["p4a returned a non-zero exit status."])

        verification, details = self.verify_artifacts([Path(a.path) for a in artifacts], request.abis)
        status = BuildStatus.SUCCEEDED if verification == VerificationStatus.VERIFIED else BuildStatus.FAILED
        return P4aBuildResult(status, command, completed.returncode, duration, self._tail(completed.stdout), self._tail(completed.stderr), artifacts, verification, details)

    def build_command(self, request: P4aBuildRequest) -> list[str]:
        command = [
            "p4a", request.artifact_kind,
            "--private", str(Path(request.project).resolve()),
            "--package", request.package_id,
            "--name", request.app_name,
            "--version", request.version,
            "--bootstrap", request.bootstrap,
            "--requirements", ",".join(request.requirements),
        ]
        for abi in request.abis:
            command.extend(["--arch", abi])
        for recipe_dir in request.recipe_dirs:
            command.extend(["--recipe-dir", str(Path(recipe_dir).resolve())])
        command.append(f"--{request.build_mode}")
        return command

    def verify_artifacts(self, artifacts: Sequence[Path], abis: Sequence[str]) -> tuple[VerificationStatus, list[str]]:
        details: list[str] = []
        if not artifacts:
            return VerificationStatus.FAILED, ["No new APK/AAB/AAR artifact was produced."]

        for artifact in artifacts:
            if not artifact.exists() or artifact.stat().st_size == 0:
                details.append(f"invalid artifact: {artifact}")
                continue
            if artifact.suffix.lower() not in {".apk", ".aab", ".aar"}:
                details.append(f"unexpected artifact type: {artifact.name}")
                continue
            if not zipfile.is_zipfile(artifact):
                details.append(f"artifact is not a readable Android ZIP container: {artifact.name}")
                continue

            with zipfile.ZipFile(artifact) as archive:
                names = archive.namelist()
                if artifact.suffix.lower() == ".apk" and "AndroidManifest.xml" not in names:
                    details.append(f"APK missing AndroidManifest.xml: {artifact.name}")
                    continue
                if artifact.suffix.lower() == ".aab" and not any(name.endswith("/manifest/AndroidManifest.xml") for name in names):
                    details.append(f"AAB missing module manifest: {artifact.name}")
                    continue
                if artifact.suffix.lower() == ".aar" and "AndroidManifest.xml" not in names:
                    details.append(f"AAR missing AndroidManifest.xml: {artifact.name}")
                    continue

                native_abis = {name.split("/")[1] for name in names if name.startswith("lib/") and len(name.split("/")) > 2}
                missing = [abi for abi in abis if native_abis and abi not in native_abis]
                if missing:
                    details.append(f"{artifact.name} missing requested native ABI(s): {', '.join(missing)}")
                    continue

            details.append(f"verified Android container: {artifact.name}")

        verified_count = sum(item.startswith("verified Android container:") for item in details)
        return (VerificationStatus.VERIFIED if verified_count == len(artifacts) else VerificationStatus.FAILED), details

    def _artifact_snapshot(self, project: Path, output_dir: str) -> set[tuple[str, int, int]]:
        root = project / output_dir
        if not root.exists():
            return set()
        return {(str(p.resolve()), p.stat().st_size, p.stat().st_mtime_ns) for p in root.rglob("*") if p.is_file() and p.suffix.lower() in {".apk", ".aab", ".aar"}}

    def _discover_new_artifacts(self, project: Path, output_dir: str, before: set[tuple[str, int, int]]) -> list[BuildArtifact]:
        root = project / output_dir
        if not root.exists():
            return []
        found: list[BuildArtifact] = []
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in {".apk", ".aab", ".aar"}:
                continue
            signature = (str(path.resolve()), path.stat().st_size, path.stat().st_mtime_ns)
            if signature in before:
                continue
            found.append(BuildArtifact(str(path.resolve()), path.suffix.lower().lstrip("."), path.stat().st_size, self._sha256(path)))
        return sorted(found, key=lambda item: item.path)

    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    @staticmethod
    def _tail(value: str, limit: int = 12000) -> str:
        return value[-limit:]

    @staticmethod
    def _request(payload: dict[str, Any]) -> P4aBuildRequest:
        allowed = set(P4aBuildRequest.__dataclass_fields__)
        return P4aBuildRequest(**{key: value for key, value in payload.items() if key in allowed})

    @staticmethod
    def _validate(request: P4aBuildRequest) -> None:
        project = Path(request.project)
        if not project.exists() or not project.is_dir():
            raise ValueError(f"project directory does not exist: {project}")
        if request.artifact_kind not in P4aBuildBackend.SUPPORTED_ARTIFACTS:
            raise ValueError(f"unsupported artifact kind: {request.artifact_kind}")
        if request.build_mode not in P4aBuildBackend.SUPPORTED_MODES:
            raise ValueError(f"unsupported build mode: {request.build_mode}")
        if not request.abis:
            raise ValueError("at least one Android ABI is required")
        if not request.requirements:
            raise ValueError("at least one p4a requirement is required")

    @staticmethod
    def _result_dict(result: P4aBuildResult) -> dict[str, Any]:
        return {
            "status": result.status.value,
            "command": result.command,
            "returncode": result.returncode,
            "duration_seconds": result.duration_seconds,
            "stdout_tail": result.stdout_tail,
            "stderr_tail": result.stderr_tail,
            "artifacts": [asdict(a) for a in result.artifacts],
            "verification": result.verification.value,
            "verification_details": result.verification_details,
        }
