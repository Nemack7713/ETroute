"""Graceful ETroute action results and CI reporting."""
from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from enum import IntEnum
from pathlib import Path
from typing import Any, Callable


class ExitCode(IntEnum):
    SUCCESS = 0
    ENVIRONMENT_FAILURE = 2
    NETWORK_FAILURE = 3
    FILESYSTEM_FAILURE = 4
    TEST_FAILURE = 10
    INTERNAL_FAILURE = 20


@dataclass(frozen=True)
class ActionResult:
    name: str
    ok: bool
    exit_code: int
    message: str
    data: dict[str, Any] | None = None


def guarded(name: str, action: Callable[[], Any], failure_code: ExitCode = ExitCode.INTERNAL_FAILURE) -> ActionResult:
    try:
        value = action()
        data = value if isinstance(value, dict) else {"result": value} if value is not None else None
        return ActionResult(name, True, int(ExitCode.SUCCESS), "ok", data)
    except (OSError, PermissionError) as exc:
        return ActionResult(name, False, int(ExitCode.FILESYSTEM_FAILURE), str(exc))
    except Exception as exc:
        return ActionResult(name, False, int(failure_code), str(exc))


def build_ci_report(*results: ActionResult) -> dict[str, Any]:
    exit_code = max((result.exit_code for result in results if not result.ok), default=0)
    return {
        "ok": all(result.ok for result in results),
        "exit_code": exit_code,
        "results": [asdict(result) for result in results],
    }


def emit_github_actions_summary(report: dict[str, Any]) -> bool:
    destination = os.environ.get("GITHUB_STEP_SUMMARY")
    if not destination:
        return False
    path = Path(destination)
    lines = ["## ETroute CI", "", f"Overall: **{'PASS' if report['ok'] else 'FAIL'}**", ""]
    for result in report["results"]:
        lines.append(f"- {'PASS' if result['ok'] else 'FAIL'} `{result['name']}` — {result['message']}")
    try:
        with path.open("a", encoding="utf-8") as stream:
            stream.write("\n".join(lines) + "\n")
        return True
    except (OSError, PermissionError):
        return False


def report_json(report: dict[str, Any]) -> str:
    return json.dumps(report, indent=2, sort_keys=True)
