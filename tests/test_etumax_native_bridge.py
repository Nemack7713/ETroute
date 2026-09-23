#!/usr/bin/env python3
from __future__ import annotations

import argparse
import unittest

from tools import etumax_native_bridge


def make_args(**overrides):
    values = {
        "pack_id": "org.etroute.apktool",
        "generation_id": "a" * 64,
        "tool_id": "apktool",
        "argument": ["decode", "input.apk"],
        "timeout_ms": 60_000,
        "request_id": "request-1",
        "session_id": "session-1",
        "event_json": None,
        "event_file": None,
        "json_out": None,
    }
    values.update(overrides)
    return argparse.Namespace(**values)


class ETumaxNativeBridgeTests(unittest.TestCase):
    def test_valid_dispatch_is_accepted_without_execution_claim(self):
        envelope, exit_code = etumax_native_bridge.build_dispatch(
            make_args(),
            now_epoch=10.125,
        )

        self.assertEqual(exit_code, 0)
        self.assertTrue(envelope["ok"])
        self.assertEqual(envelope["state"], "ACCEPTED")
        self.assertEqual(envelope["route"], "etroute.native.runtime-pack")
        self.assertEqual(envelope["request_id"], "request-1")
        self.assertEqual(envelope["session_id"], "session-1")
        self.assertEqual(envelope["accepted_at_ms"], 10125)
        self.assertFalse(envelope["delivery"]["delivered"])
        self.assertFalse(envelope["delivery"]["executed"])
        self.assertEqual(
            envelope["dispatch"]["generation_id"],
            "a" * 64,
        )

    def test_invalid_generation_is_rejected(self):
        envelope, exit_code = etumax_native_bridge.build_dispatch(
            make_args(generation_id="not-a-generation"),
            now_epoch=20.0,
        )

        self.assertEqual(exit_code, 22)
        self.assertFalse(envelope["ok"])
        self.assertEqual(envelope["state"], "REJECTED")
        self.assertEqual(
            envelope["error"]["kind"],
            "native_dispatch_contract_error",
        )

    def test_no_proot_or_rootfs_arguments_exist(self):
        parser = etumax_native_bridge.build_parser()
        option_strings = {
            option
            for action in parser._actions
            for option in action.option_strings
        }

        self.assertNotIn("--proot", option_strings)
        self.assertNotIn("--rootfs", option_strings)
        self.assertNotIn("--guest-python", option_strings)

    def test_event_correlation_is_preserved(self):
        event = (
            '{"schema_version":1,'
            '"event_id":"evt-1",'
            '"request_id":"request-event",'
            '"session_id":"session-event",'
            '"type":"runtime.execute",'
            '"source":"etumax",'
            '"severity":"info",'
            '"created_at":"2026-09-22T23:00:00+00:00",'
            '"payload":{}}'
        )
        args = make_args(
            request_id=None,
            session_id=None,
            event_json=event,
        )

        envelope, exit_code = etumax_native_bridge.build_dispatch(
            args,
            now_epoch=1_790_118_000,
        )

        self.assertEqual(exit_code, 0)
        self.assertEqual(envelope["request_id"], "request-event")
        self.assertEqual(envelope["session_id"], "session-event")
        self.assertTrue(envelope["acknowledgement"]["accepted"])
        self.assertEqual(
            envelope["acknowledgement"]["route"],
            "etroute.native.runtime-pack",
        )


if __name__ == "__main__":
    unittest.main()
