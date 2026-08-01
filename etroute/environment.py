"""Safe binary-environment detection for ETroute."""
from __future__ import annotations

import os
import platform
import shutil
import sys
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class EnvironmentSnapshot:
    kind: str
    runtime: str
    python_executable: str
    python_version: str
    platform: str
    binaries: dict[str, str | None]
    markers: tuple[str, ...]

    def as_dict(self) -> dict[str, object]:
        return asdict(self)


def _exists(path: str) -> bool:
    try:
        return Path(path).exists()
    except (OSError, PermissionError):
        return False


def _readable_contains(path: str, needle: str) -> bool:
    try:
        return needle.lower() in Path(path).read_text(errors="ignore").lower()
    except (OSError, PermissionError):
        return False


def detect_environment() -> EnvironmentSnapshot:
    markers: list[str] = []
    kind = "host"
    runtime = "host"

    if _exists("/.dockerenv") or _readable_contains("/proc/1/cgroup", "docker"):
        kind, runtime = "container", "docker"
        markers.append("container")
    elif os.environ.get("PROOT_TMP_DIR") or os.environ.get("TERMUX_VERSION") or _exists("/.proot"):
        kind, runtime = "proot", "termux/proot"
        markers.append("proot")
    elif os.environ.get("ANDROID_ROOT") or _exists("/system/build.prop"):
        kind, runtime = "host", "android"
        markers.append("android")

    names = ("python", "python3", "proot", "bash", "sh", "curl", "git", "java", "adb")
    binaries = {name: shutil.which(name) for name in names}
    return EnvironmentSnapshot(
        kind=kind,
        runtime=runtime,
        python_executable=sys.executable,
        python_version=platform.python_version(),
        platform=platform.platform(),
        binaries=binaries,
        markers=tuple(markers),
    )
