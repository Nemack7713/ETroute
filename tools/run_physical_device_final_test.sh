#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f local.properties ]]; then
  SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n 1)"
fi
if [[ -z "$SDK_ROOT" ]]; then
  SDK_ROOT="$HOME/.android-sdk"
fi

ADB="$SDK_ROOT/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then
  echo "[ETroute] ERROR: adb not found at $ADB" >&2
  exit 2
fi

mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2 == "device" {print $1}')
if [[ ${#DEVICES[@]} -ne 1 ]]; then
  echo "[ETroute] ERROR: connect exactly one ready Android device for the final gate." >&2
  "$ADB" devices -l >&2
  exit 3
fi

SERIAL="${DEVICES[0]}"
ABI="$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
API="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
MODEL="$("$ADB" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
MEM_KB="$("$ADB" -s "$SERIAL" shell awk '/MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null | tr -d '\r' || true)"

cat <<EOF
[ETroute] Physical-device final gate
[ETroute] serial=$SERIAL
[ETroute] model=$MODEL
[ETroute] abi=$ABI
[ETroute] api=$API
[ETroute] memTotalKb=${MEM_KB:-unknown}
EOF

if [[ "$ABI" != "arm64-v8a" ]]; then
  echo "[ETroute] ERROR: final production device must be arm64-v8a; got $ABI" >&2
  exit 4
fi

if [[ -n "$API" && "$API" -lt 24 ]]; then
  echo "[ETroute] ERROR: device API $API is below minSdk 24." >&2
  exit 4
fi

export ANDROID_SERIAL="$SERIAL"

# The normal validator rebuilds both ABIs, verifies native ELF/JNI exports,
# then runs the complete AndroidJUnitRunner suite on this selected device.
bash tools/run_android_jni_validation.sh

REPORT_DIR="$ROOT_DIR/android/build/reports/androidTests/connected"
if [[ ! -d "$REPORT_DIR" ]]; then
  echo "[ETroute] ERROR: connected Android test report was not produced." >&2
  exit 5
fi

if grep -RqiE '>[^<]*failed[^<]*</|failures[^0-9]*[1-9]' "$REPORT_DIR" 2>/dev/null; then
  echo "[ETroute] ERROR: report contains test failures. Review: $REPORT_DIR" >&2
  exit 6
fi

cat <<EOF
[ETroute] FINAL_ANDROID_DEVICE_GATE_PASSED
[ETroute] JNI_NDK_BUILD_VERIFIED
[ETroute] JNI_DEVICE_VERIFIED
[ETroute] Native supervisor, validator, workspace runner, timeout cleanup, output permissions,
[ETroute] resource limits, workspace escape rejection, intentional output preservation, and
[ETroute] bounded diagnostics all passed on the connected arm64 device.
[ETroute] Report: $REPORT_DIR
EOF
