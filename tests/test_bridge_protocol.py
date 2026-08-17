#!/usr/bin/env python3
from __future__ import annotations

import json
import unittest
from datetime import datetime, timezone

from bridge_protocol import EventAcknowledgement, EventEnvelope, ProtocolError, Severity


class EventProtocolTests(unittest.TestCase):
    def test_roundtrip_preserves_protocol_fields(self) -> None:
        event = EventEnvelope(
            event_type="notification.alert",
            source="etumax",
            severity="urgent",
            request_id="req-1",
            session_id="session-1",
            ttl_seconds=30,
            dedupe_key="SPY:below:500",
            payload={"symbol": "SPY", "price": 499.5},
        )
        restored = EventEnvelope.from_json(event.to_json())
        self.assertEqual(restored.event_type, "notification.alert")
        self.assertEqual(restored.source, "etumax")
        self.assertEqual(restored.severity, Severity.URGENT)
        self.assertEqual(restored.request_id, "req-1")
        self.assertEqual(restored.session_id, "session-1")
        self.assertEqual(restored.dedupe_key, "SPY:below:500")
        self.assertEqual(restored.payload["price"], 499.5)

    def test_severity_has_stable_priority_order(self) -> None:
        severities = [Severity.INFO, Severity.CRITICAL, Severity.WARNING, Severity.URGENT]
        self.assertEqual(
            sorted(severities),
            [Severity.CRITICAL, Severity.URGENT, Severity.WARNING, Severity.INFO],
        )

    def test_expiry_is_deterministic(self) -> None:
        created = datetime(2026, 8, 17, 20, 0, tzinfo=timezone.utc)
        event = EventEnvelope(
            event_type="runner.completed",
            source="etumax",
            payload={},
            created_at=created.isoformat(),
            ttl_seconds=10,
        )
        self.assertFalse(event.is_expired(now_epoch=created.timestamp() + 9))
        self.assertTrue(event.is_expired(now_epoch=created.timestamp() + 10))

    def test_invalid_severity_is_rejected(self) -> None:
        with self.assertRaises(ProtocolError):
            EventEnvelope(
                event_type="notification.alert",
                source="etumax",
                severity="panic",
                payload={},
            )

    def test_unknown_wire_fields_are_rejected(self) -> None:
        raw = {
            "schema_version": 1,
            "type": "diagnostic.result",
            "source": "etroute",
            "payload": {},
            "severity": "info",
            "event_id": "evt-1",
            "created_at": datetime.now(timezone.utc).isoformat(),
            "surprise": True,
        }
        with self.assertRaises(ProtocolError):
            EventEnvelope.from_json(json.dumps(raw))

    def test_non_json_payload_is_rejected(self) -> None:
        with self.assertRaises(ProtocolError):
            EventEnvelope(
                event_type="diagnostic.result",
                source="etroute",
                payload={"bad": object()},
            )

    def test_acknowledgement_distinguishes_acceptance_and_route(self) -> None:
        ack = EventAcknowledgement(
            event_id="evt-123",
            accepted=True,
            route="android.notification",
            status="accepted",
        )
        decoded = json.loads(ack.to_json())
        self.assertTrue(decoded["accepted"])
        self.assertEqual(decoded["route"], "android.notification")
        self.assertEqual(decoded["event_id"], "evt-123")


if __name__ == "__main__":
    unittest.main()
