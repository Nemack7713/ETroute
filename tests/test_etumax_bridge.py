#!/usr/bin/env python3
"""Deterministic tests for the ETumax-to-ETroute execution bridge."""
from __future__ import annotations

import argparse
import subprocess
import unittest
from unittest.mock import patch

from tools import etumax_bridge


def make_args() -> argparse.Namespace:
    return argparse.Namespace(
        environment="default",
        etroute="etroute.py",
        python="python3",
        guest_python="/usr/bin/env",
        proot="proot",
        kernel_release=None,
        kill_on_exit=False,
        timeout=60,
        request_id="request-1",
        session_id="session-1",
        json_out=None,
    )


def valid_diagnostic() -> str:
    return (
        '{"schema_version":1,'
        '"resources":{"required_binds_visible":true},'
        '"device":{"kernel_release_matches":true},'
        '"activation":{"marker":{}},'
        '"integration":{"declared_caller":"etumax",'
        '"environment_name":"default","session_id":"session-1"}}'
    )


class ETumaxBridgeTests(unittest.TestCase):
    @patch("tools.etumax_bridge.time.time", side_effect=[10.0, 10.125])
    @patch("tools.etumax_bridge.subprocess.run")
    def test_success_is_completed_terminal_session(self, run, _time):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout=valid_diagnostic(), stderr=""
        )

        envelope, exit_code = etumax_bridge.run_bridge(make_args())

        self.assertEqual(exit_code, 0)
        self.assertTrue(envelope["ok"])
        self.assertEqual(envelope["schema_version"], 2)
        self.assertEqual(envelope["backend"], "etroute")
        self.assertEqual(envelope["state"], "COMPLETED")
        self.assertEqual(envelope["session_id"], "session-1")
        self.assertEqual(envelope["started_at_ms"], 10000)
        self.assertEqual(envelope["finished_at_ms"], 10125)
        self.assertEqual(envelope["duration_ms"], 125)
        self.assertEqual(envelope["process"]["exit_code"], 0)
        self.assertEqual(envelope["process"]["stdout"], valid_diagnostic())

    @patch("tools.etumax_bridge.time.time", side_effect=[20.0, 20.050])
    @patch("tools.etumax_bridge.subprocess.run")
    def test_contract_failure_is_failed_terminal_session(self, run, _time):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="not-json", stderr="diagnostic stderr"
        )

        envelope, exit_code = etumax_bridge.run_bridge(make_args())

        self.assertEqual(exit_code, 21)
        self.assertFalse(envelope["ok"])
        self.assertEqual(envelope["state"], "FAILED")
        self.assertEqual(envelope["duration_ms"], 50)
        self.assertEqual(envelope["process"]["stdout"], "not-json")
        self.assertEqual(envelope["process"]["stderr"], "diagnostic stderr")
        self.assertEqual(envelope["error"]["kind"], "contract_error")

    @patch("tools.etumax_bridge.time.time", side_effect=[30.0, 30.250])
    @patch("tools.etumax_bridge.subprocess.run")
    def test_timeout_is_failed_without_claiming_timed_out_state(self, run, _time):
        run.side_effect = subprocess.TimeoutExpired(cmd=["etroute"], timeout=60)

        envelope, exit_code = etumax_bridge.run_bridge(make_args())

        self.assertEqual(exit_code, 20)
        self.assertFalse(envelope["ok"])
        self.assertEqual(envelope["state"], "FAILED")
        self.assertEqual(envelope["error"]["kind"], "timeout")
        self.assertEqual(envelope["duration_ms"], 250)

    def test_parser_rejects_trailing_stdout(self):
        with self.assertRaises(etumax_bridge.ContractError):
            etumax_bridge.parse_single_json_document('{"ok":true}\nnoise')


if __name__ == "__main__":
    unittest.main()
