#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

AVD_NAME="${ETROUTE_AVD_NAME:-ETroute_API_36_x86_64}"
EMULATOR_PORT="${ETROUTE_EMULATOR_PORT:-5554}"
SERIAL="emulator-${EMULATOR_PORT}"
EVIDENCE_DIR="$ROOT_DIR/evidence/android_jni/latest"
EMULATOR_LOG="$EVIDENCE_DIR/emulator-${SERIAL}.log"
BOOT_LOG="$EVIDENCE_DIR/emulator-${SERIAL}-boot.txt"
mkdir -p "$EVIDENCE_DIR"

if [[ ! -e /dev/kvm ]]; then
  echo "[ETroute] ERROR: /dev/kvm is unavailable; accelerated x86_64 emulator validation cannot run here." >&2
  exit 3
fi

detect_sdk_root() {
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"
    return 0
  fi
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    printf '%s\n' "$ANDROID_HOME"
    return 0
  fi
  if [[ -f "$ROOT_DIR/local.properties" ]]; then
    local sdk
    sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | tail -n 1)"
    if [[ -n "$sdk" && -d "$sdk" ]]; then
      printf '%s\n' "$sdk"
      return 0
    fi
  fi
  if [[ -d "$HOME/.android-sdk" ]]; then
    printf '%s\n' "$HOME/.android-sdk"
    return 0
  fi
  return 1
}

SDK_ROOT="$(detect_sdk_root || true)"
if [[ -z "$SDK_ROOT" ]]; then
  echo "[ETroute] ERROR: Android SDK root not found." >&2
  echo "[ETroute] Run: ETROUTE_ACCEPT_ANDROID_SDK_LICENSES=1 bash tools/bootstrap_android_sdk.sh" >&2
  exit 2
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$SDK_ROOT/emulator/emulator"
ADB="$SDK_ROOT/platform-tools/adb"

for tool in "$SDKMANAGER" "$AVDMANAGER" "$ADB"; do
  if [[ ! -x "$tool" ]]; then
    echo "[ETroute] ERROR: required Android tool missing: $tool" >&2
    exit 2
  fi
done

if "$ADB" devices | awk 'NR>1 && $1 == "'"$SERIAL"'" && $2 == "device" {found=1} END {exit !found}'; then
  echo "[ETroute] Existing emulator already ready: $SERIAL"
else
  echo "[ETroute] Installing Android Emulator package if needed."
  "$SDKMANAGER" --sdk_root="$SDK_ROOT" "emulator"

  if [[ ! -x "$EMULATOR" ]]; then
    echo "[ETroute] ERROR: emulator binary missing after package installation: $EMULATOR" >&2
    exit 2
  fi

  echo "[ETroute] Discovering an Android API 36 x86_64 system image."
  SDK_LIST="$($SDKMANAGER --sdk_root="$SDK_ROOT" --list 2>/dev/null || true)"
  SYSTEM_IMAGE=""
  for candidate in \
    "system-images;android-36;google_apis;x86_64" \
    "system-images;android-36;default;x86_64" \
    "system-images;android-36;google_apis_playstore;x86_64"; do
    if grep -Fq "$candidate" <<<"$SDK_LIST"; then
      SYSTEM_IMAGE="$candidate"
      break
    fi
  done

  if [[ -z "$SYSTEM_IMAGE" ]]; then
    echo "[ETroute] ERROR: no supported Android 36 x86_64 system image was advertised by sdkmanager." >&2
    echo "[ETroute] Available Android 36 x86_64 candidates:" >&2
    grep -F "system-images;android-36;" <<<"$SDK_LIST" | grep -F "x86_64" | head -n 20 >&2 || true
    exit 5
  fi

  echo "[ETroute] Selected system image: $SYSTEM_IMAGE"
  "$SDKMANAGER" --sdk_root="$SDK_ROOT" "$SYSTEM_IMAGE"

  if ! "$EMULATOR" -list-avds 2>/dev/null | grep -Fxq "$AVD_NAME"; then
    echo "[ETroute] Creating AVD: $AVD_NAME"
    printf 'no\n' | "$AVDMANAGER" create avd \
      --force \
      --name "$AVD_NAME" \
      --package "$SYSTEM_IMAGE"
  else
    echo "[ETroute] Reusing existing AVD: $AVD_NAME"
  fi

  echo "[ETroute] Starting headless accelerated emulator on port $EMULATOR_PORT."
  : > "$EMULATOR_LOG"
  nohup "$EMULATOR" \
    -avd "$AVD_NAME" \
    -port "$EMULATOR_PORT" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -gpu swiftshader_indirect \
    -accel on \
    >"$EMULATOR_LOG" 2>&1 &
  EMULATOR_PID=$!
  echo "[ETroute] emulator pid=$EMULATOR_PID log=$EMULATOR_LOG"

  cleanup_emulator() {
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
      "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    elif kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
      kill "$EMULATOR_PID" >/dev/null 2>&1 || true
    fi
  }
  trap cleanup_emulator EXIT

  echo "[ETroute] Waiting for emulator transport: $SERIAL"
  transport_deadline=$((SECONDS + 180))
  until "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; do
    if (( SECONDS >= transport_deadline )); then
      echo "[ETroute] ERROR: emulator transport did not become available." >&2
      tail -n 120 "$EMULATOR_LOG" >&2 || true
      exit 6
    fi
    if ! kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
      echo "[ETroute] ERROR: emulator process exited before adb transport became ready." >&2
      tail -n 120 "$EMULATOR_LOG" >&2 || true
      exit 6
    fi
    sleep 2
  done

  echo "[ETroute] Waiting for Android boot completion."
  boot_deadline=$((SECONDS + 240))
  until [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if (( SECONDS >= boot_deadline )); then
      echo "[ETroute] ERROR: emulator did not complete boot within timeout." >&2
      "$ADB" -s "$SERIAL" shell getprop > "$BOOT_LOG" 2>/dev/null || true
      tail -n 120 "$EMULATOR_LOG" >&2 || true
      exit 7
    fi
    sleep 2
  done
fi

{
  echo "serial=$SERIAL"
  echo "abi=$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "api=$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "release=$("$ADB" -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
  echo "boot_completed=$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')"
} | tee "$BOOT_LOG"

ABI="$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
if [[ "$ABI" != "x86_64" ]]; then
  echo "[ETroute] ERROR: expected x86_64 emulator, got: $ABI" >&2
  exit 8
fi

# Reduce test flakiness from first-boot UI animation state.
"$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true

export ANDROID_SERIAL="$SERIAL"
echo "[ETroute] Running authoritative JNI instrumentation gate on $SERIAL."
set +e
bash tools/run_android_jni_validation.sh
VALIDATION_STATUS=$?
set -e

if [[ $VALIDATION_STATUS -ne 0 ]]; then
  echo "[ETroute] JNI emulator validation failed with status $VALIDATION_STATUS." >&2
  exit "$VALIDATION_STATUS"
fi

echo "[ETroute] JNI_DEVICE_VERIFIED on $SERIAL"
echo "[ETroute] Device ABI: $ABI"
echo "[ETroute] Review android/build/reports/androidTests/connected/ for the test report."
