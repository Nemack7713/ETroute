#!/usr/bin/env python3
"""ETumax-to-ETroute native RuntimePack dispatch adapter.

Validates ETumax correlation/event input and emits one machine-readable
RuntimePack dispatch envelope for the Android-native ETroute control plane.

This adapter intentionally contains no PRoot, rootfs, subprocess execution, or
Android transport implementation. Acceptance means the request is valid for
delivery; it does not claim that Android execution has completed.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from bridge_protocol import EventAcknowledgement, EventEnvelope, ProtocolError

SCHEMA_VERSION = 1
ROUTE = "etroute.native.runtime-pack"
STATE_ACCEPTED = "ACCEPTED"
STATE_REJECTED = "REJECTED"

PACK_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
TOOL_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
GENERATION_RE = re.compile(r"^[0-9a-f]{64}$")
MAX_ARGS = 256
MAX_ARG_LENGTH = 32_768


class NativeBridgeError(ValueError):
    pass


def _require_text(name: str, value: str | None, *, maximum: int = 256) -> str:
    cleaned = "" if value is None else str(value).strip()
    if not cleaned:
        raise NativeBridgeError(f"{name} cannot be empty")
    if len(cleaned) > maximum:
        raise NativeBridgeError(f"{name} exceeds {maximum} characters")
    if "\x00" in cleaned:
        raise NativeBridgeError(f"{name} contains NUL")
    return cleaned


def _require_match(name: str, value: str, pattern: re.Pattern[str]) -> str:
    cleaned = _require_text(name, value)
    if pattern.fullmatch(cleaned) is None:
        raise NativeBridgeError(f"invalid {name}: {cleaned!r}")
    return cleaned


def _validate_arguments(values: Sequence[str]) -> tuple[str, ...]:
    if len(values) > MAX_ARGS:
        raise NativeBridgeError(f"arguments exceeds {MAX_ARGS} entries")
    result: list[str] = []
    for value in values:
        text = str(value)
        if "\x00" in text:
            raise NativeBridgeError("argument contains NUL")
        if len(text) > MAX_ARG_LENGTH:
            raise NativeBridgeError(
                f"argument exceeds {MAX_ARG_LENGTH} characters"
            )
        result.append(text)
    return tuple(result)


@dataclass(frozen=True)
class NativeDispatchRequest:
    request_id: str
    session_id: str
    pack_id: str
    generation_id: str
    tool_id: str
    arguments: tuple[str, ...]
    timeout_ms: int
    origin: str = "etumax"
    schema_version: int = SCHEMA_VERSION

    def __post_init__(self) -> None:
        if self.schema_version != SCHEMA_VERSION:
            raise NativeBridgeError(
                f"unsupported native dispatch schema_version: {self.schema_version}"
            )
        object.__setattr__(
            self, "request_id", _require_text("request_id", self.request_id, maximum=128)
        )
        object.__setattr__(
            self, "session_id", _require_text("session_id", self.session_id, maximum=128)
        )
        object.__setattr__(
            self, "pack_id", _require_match("pack_id", self.pack_id, PACK_ID_RE)
        )
        object.__setattr__(
            self,
            "generation_id",
            _require_match("generation_id", self.generation_id, GENERATION_RE),
        )
        object.__setattr__(
            self, "tool_id", _require_match("tool_id", self.tool_id, TOOL_ID_RE)
        )
        object.__setattr__(self, "arguments", _validate_arguments(self.arguments))
        if isinstance(self.timeout_ms, bool) or not isinstance(self.timeout_ms, int):
            raise NativeBridgeError("timeout_ms must be an integer")
        if self.timeout_ms <= 0:
            raise NativeBridgeError("timeout_ms must be greater than zero")
        if self.timeout_ms > 86_400_000:
            raise NativeBridgeError("timeout_ms exceeds 24 hours")
        if self.origin != "etumax":
            raise NativeBridgeError("native dispatch origin must be 'etumax'")

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["arguments"] = list(self.arguments)
        return result


def load_event(args: argparse.Namespace) -> EventEnvelope | None:
    event_json = getattr(args, "event_json", None)
    event_file = getattr(args, "event_file", None)
    if not event_json and not event_file:
        return None

    try:
        text = (
            event_json
            if event_json is not None
            else Path(event_file).expanduser().resolve().read_text(encoding="utf-8")
        )
        event = EventEnvelope.from_json(text)
    except (OSError, ProtocolError) as exc:
        raise NativeBridgeError(f"invalid Event Protocol v1 input: {exc}") from exc

    if event.source.lower() != "etumax":
        raise NativeBridgeError(
            f"native bridge requires event source 'etumax', got {event.source!r}"
        )
    if event.is_expired():
        raise NativeBridgeError(f"event {event.event_id!r} has expired")
    return event


def resolve_correlation(
    args: argparse.Namespace,
    event: EventEnvelope | None,
) -> tuple[str, str]:
    if event is not None:
        if args.request_id and event.request_id and args.request_id != event.request_id:
            raise NativeBridgeError("event request_id conflicts with --request-id")
        if args.session_id and event.session_id and args.session_id != event.session_id:
            raise NativeBridgeError("event session_id conflicts with --session-id")

    request_id = (
        args.request_id
        or (event.request_id if event is not None else None)
        or f"runreq-{uuid.uuid4().hex}"
    )
    session_id = (
        args.session_id
        or (event.session_id if event is not None else None)
        or f"session-{uuid.uuid4().hex}"
    )
    return request_id, session_id


def _base_envelope(
    *,
    request_id: str,
    session_id: str,
    state: str,
    accepted_at_ms: int,
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "route": ROUTE,
        "state": state,
        "request_id": request_id,
        "session_id": session_id,
        "accepted_at_ms": accepted_at_ms,
    }


def build_dispatch(
    args: argparse.Namespace,
    *,
    now_epoch: float | None = None,
) -> tuple[dict[str, Any], int]:
    event: EventEnvelope | None = None
    accepted_at_ms = int((time.time() if now_epoch is None else now_epoch) * 1000)

    try:
        event = load_event(args)
        request_id, session_id = resolve_correlation(args, event)
        request = NativeDispatchRequest(
            request_id=request_id,
            session_id=session_id,
            pack_id=args.pack_id,
            generation_id=args.generation_id,
            tool_id=args.tool_id,
            arguments=tuple(args.argument or ()),
            timeout_ms=args.timeout_ms,
        )
    except NativeBridgeError as exc:
        request_id = (
            getattr(args, "request_id", None)
            or (event.request_id if event is not None else None)
            or f"runreq-{uuid.uuid4().hex}"
        )
        session_id = (
            getattr(args, "session_id", None)
            or (event.session_id if event is not None else None)
            or f"session-{uuid.uuid4().hex}"
        )
        envelope = _base_envelope(
            request_id=request_id,
            session_id=session_id,
            state=STATE_REJECTED,
            accepted_at_ms=accepted_at_ms,
        )
        envelope["ok"] = False
        envelope["error"] = {
            "kind": "native_dispatch_contract_error",
            "message": str(exc),
        }
        if event is not None:
            envelope["event"] = event.to_dict()
            envelope["acknowledgement"] = EventAcknowledgement(
                event_id=event.event_id,
                accepted=False,
                status="rejected",
                message=str(exc),
            ).to_dict()
        return envelope, 22

    envelope = _base_envelope(
        request_id=request.request_id,
        session_id=request.session_id,
        state=STATE_ACCEPTED,
        accepted_at_ms=accepted_at_ms,
    )
    envelope.update(
        {
            "ok": True,
            "dispatch": request.to_dict(),
            "delivery": {
                "delivered": False,
                "executed": False,
                "message": (
                    "validated for ETroute Android-native RuntimePack delivery; "
                    "execution is not implied"
                ),
            },
        }
    )

    if event is not None:
        envelope["event"] = event.to_dict()
        envelope["acknowledgement"] = EventAcknowledgement(
            event_id=event.event_id,
            accepted=True,
            route=ROUTE,
            status="accepted",
            message="validated for Android-native RuntimePack dispatch",
        ).to_dict()

    return envelope, 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--pack-id", required=True)
    parser.add_argument("--generation-id", required=True)
    parser.add_argument("--tool-id", required=True)
    parser.add_argument("--arg", dest="argument", action="append", default=[])
    parser.add_argument("--timeout-ms", type=int, default=60_000)
    parser.add_argument("--request-id")
    parser.add_argument("--session-id")
    event_group = parser.add_mutually_exclusive_group()
    event_group.add_argument("--event-json")
    event_group.add_argument("--event-file", type=Path)
    parser.add_argument("--json-out", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    envelope, exit_code = build_dispatch(args)
    rendered = json.dumps(envelope, indent=2, sort_keys=True) + "\n"
    print(rendered, end="")

    if args.json_out:
        destination = args.json_out.expanduser().resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(
            f".{destination.name}.tmp-{uuid.uuid4().hex}"
        )
        temporary.write_text(rendered, encoding="utf-8")
        temporary.replace(destination)

    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
