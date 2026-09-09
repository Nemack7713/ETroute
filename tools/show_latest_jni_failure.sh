#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT_DIR/evidence/android_jni/latest/gradle-build.log"

if [[ ! -f "$LOG" ]]; then
  echo "[ETroute] ERROR: latest Gradle JNI log not found: $LOG" >&2
  exit 2
fi

echo "[ETroute] Latest JNI Gradle root-cause summary"
echo "[ETroute] Log: $LOG"
echo

awk '
  /^\* What went wrong:/ { in_block=1 }
  in_block { print }
  in_block && /^\* Try:/ { exit }
' "$LOG"

echo
echo "[ETroute] First relevant cause/error lines"
grep -nEi -m 20 \
  'Caused by:|Execution failed for task|CMake Error|error:|SDK location not found|Android SDK|NDK|Could not resolve|Plugin .* was not found|Minimum supported Gradle|Maximum supported Gradle|Android Gradle plugin requires Java|Unsupported class file|No matching variant|Could not determine the dependencies' \
  "$LOG" || true
