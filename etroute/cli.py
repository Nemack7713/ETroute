"""Scalable ETroute command-line surface."""
from __future__ import annotations

import argparse
import sys
import unittest

from .cloud import provider
from .devops import ExitCode, build_ci_report, emit_github_actions_summary, guarded, report_json
from .environment import detect_environment


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m etroute")
    parser.add_argument("--env", action="store_true", help="show detected execution environment")
    parser.add_argument("--test", action="store_true", help="run ETroute unittest discovery")
    parser.add_argument("--ci", action="store_true", help="emit machine-readable CI report")
    parser.add_argument("--github", action="store_true", help="append CI results to GITHUB_STEP_SUMMARY")
    parser.add_argument("--cloud", choices=("aws", "gcp", "azure"), help="include provider readiness status")
    return parser


def _tests() -> dict[str, object]:
    suite = unittest.defaultTestLoader.discover("tests")
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    if not result.wasSuccessful():
        raise RuntimeError(
            f"tests failed: failures={len(result.failures)} errors={len(result.errors)}"
        )
    return {
        "tests_run": result.testsRun,
        "failures": len(result.failures),
        "errors": len(result.errors),
    }


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    environment = detect_environment()

    if args.env and not args.ci:
        print(
            f"Environment   {environment.kind} ({environment.runtime})  "
            f"python={environment.python_version}  {environment.platform}"
        )
        for name, path in environment.binaries.items():
            print(f"{name:10} {path or '-'}")

    results = [
        guarded(
            "environment",
            environment.as_dict,
            ExitCode.ENVIRONMENT_FAILURE,
        )
    ]

    if args.test or args.ci:
        results.append(guarded("tests", _tests, ExitCode.TEST_FAILURE))

    if args.cloud:
        results.append(
            guarded(
                f"cloud:{args.cloud}",
                lambda: provider(args.cloud).health().as_dict(),
                ExitCode.NETWORK_FAILURE,
            )
        )

    report = build_ci_report(*results)
    if args.ci:
        print(report_json(report))
        if args.github:
            emit_github_actions_summary(report)
    elif args.test:
        print("PASS" if report["ok"] else "FAIL")

    return int(report["exit_code"])


if __name__ == "__main__":
    sys.exit(main())
