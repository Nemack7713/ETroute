#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

AVD_NAME="${ETROUTE_AVD_NAME:-ETroute_API_36_x86_64}"
EMULATOR_PORT="${ETROUTE_EMULATOR_PORT:-5554}"
EMULATOR_DATA_MB="${ETROUTE_EMULATOR_DATA_MB:-3072}"
EMULATOR_MEMORY_MB="${ETROUTE_EMULATOR_MEMORY_MB:-2048}"
MIN_HOST_RESERVE_MB="${ETROUTE_EMULATOR_HOST_RESERVE_MB:-2048}"
SERIAL="emulator-${EMULATOR_PORT}"
EVIDENCE_DIR="$ROOT_DIR/evidence/android_jni/latest"
EMULATOR_LOG="$EVIDENCE_DIR/emulator-${SERIAL}.log"
BOOT_LOG="$EVIDENCE_DIR/emulator-${SERIAL}-boot.txt"
KVM_LOG="$EVIDENCE_DIR/kvm-access.txt"
mkdir -p "$EVIDENCE_DIR"

if [[ ! -e /dev/kvm ]]; then
  echo "[ETroute] ERROR: /dev/kvm is unavailable; accelerated x86_64 emulator validation cannot run here." >&2
  exit 3
fi

for numeric in "$EMULATOR_DATA_MB" "$EMULATOR_MEMORY_MB" "$MIN_HOST_RESERVE_MB"; do
  if ! [[ "$numeric" =~ ^[0-9]+$ ]] || [[ "$numeric" -le 0 ]]; then
    echo "[ETroute] ERROR: emulator size settings must be positive integer MB values." >&2
    exit 2
  fi
done

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

write_kvm_evidence() {
  {
    echo "user=$(id -un)"
    echo "uid=$(id -u)"
    echo "groups=$(id -Gn)"
    echo "device=$(ls -l /dev/kvm 2>&1 || true)"
    echo "device_stat=$(stat -c 'mode=%A uid=%u gid=%g owner=%U group=%G' /dev/kvm 2>&1 || true)"
    echo "readable=$([[ -r /dev/kvm ]] && echo true || echo false)"
    echo "writable=$([[ -w /dev/kvm ]] && echo true || echo false)"
    echo "kvm_group=$(getent group kvm 2>&1 || true)"
  } > "$KVM_LOG"
}

explain_kvm_permission_failure() {
  write_kvm_evidence
  echo "[ETroute] ERROR: /dev/kvm exists, but the current Codespaces user cannot use it." >&2
  echo "[ETroute] KVM evidence: $KVM_LOG" >&2
  cat "$KVM_LOG" >&2
  echo >&2
  echo "[ETroute] This is a permission/access problem, not an ETroute JNI or emulator-image failure." >&2
  echo "[ETroute] Preferred immediate repair for this ephemeral Codespace:" >&2
  echo "  sudo apt-get update && sudo apt-get install -y acl" >&2
  echo "  sudo setfacl -m u:\"\$USER\":rw /dev/kvm" >&2
  echo >&2
  echo "[ETroute] Then verify acceleration before rerunning:" >&2
  echo "  $SDK_ROOT/emulator/emulator -accel-check" >&2
  echo >&2
  echo "[ETroute] If ACL changes are blocked by the Codespaces host, do not weaken ETroute or disable acceleration." >&2
  echo "[ETroute] Use a reachable physical Android device/emulator for JNI_DEVICE_VERIFIED instead." >&2
}

if "$ADB" devices | awk 'NR>1 && $1 == "'"$SERIAL"'" && $2 == "device" {found=1} END {exit !found}'; then
  echo "[ETroute] Existing emulator already ready: $SERIAL"
else
  echo "[ETroute] Installing Android Emulator package if needed."
  "$SDKMANAGER" --sdk_root="$SDK_ROOT" "emulator"

  if [[ ! -x "$EMULATOR" ]]; then
    echo "[ETroute] ERROR: emulator binary missing after package installation: $EMULATOR" >&2
    exit 2
  fi

  # /dev/kvm existing is not enough. The user must be able to open it read/write,
  # and the Android emulator must report usable acceleration.
  if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
    explain_kvm_permission_failure
    exit 10
  fi

  write_kvm_evidence
  set +e
  ACCEL_OUTPUT="$($EMULATOR -accel-check 2>&1)"
  ACCEL_STATUS=$?
  set -e
  printf '%s\n' "$ACCEL_OUTPUT" >> "$KVM_LOG"
  if [[ $ACCEL_STATUS -ne 0 ]] || ! grep -Eqi 'accel|KVM' <<<"$ACCEL_OUTPUT"; then
    echo "[ETroute] ERROR: Android Emulator acceleration check failed." >&2
    echo "$ACCEL_OUTPUT" >&2
    explain_kvm_permission_failure
    exit 10
  fi
  echo "[ETroute] KVM acceleration check passed."

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

  AVAILABLE_MB="$(df -Pm "$HOME" | awk 'NR==2 {print $4}')"
  REQUIRED_MB=$((EMULATOR_DATA_MB + MIN_HOST_RESERVE_MB))
  echo "[ETroute] Host disk free: ${AVAILABLE_MB} MB"
  echo "[ETroute] Emulator userdata: ${EMULATOR_DATA_MB} MB; host reserve: ${MIN_HOST_RESERVE_MB} MB"
  if [[ -z "$AVAILABLE_MB" || "$AVAILABLE_MB" -lt "$REQUIRED_MB" ]]; then
    echo "[ETroute] ERROR: insufficient host disk space for the reduced validation AVD." >&2
    echo "[ETroute] Need at least ${REQUIRED_MB} MB free; found ${AVAILABLE_MB:-unknown} MB." >&2
    exit 9
  fi

  AVD_DIR="$HOME/.android/avd/${AVD_NAME}.avd"
  if [[ -d "$AVD_DIR" ]]; then
    rm -f \
      "$AVD_DIR/userdata-qemu.img" \
      "$AVD_DIR/userdata-qemu.img.qcow2" \
      "$AVD_DIR/cache.img" \
      "$AVD_DIR/cache.img.qcow2"
  fi

  echo "[ETroute] Starting headless accelerated emulator on port $EMULATOR_PORT."
  echo "[ETroute] Using reduced ${EMULATOR_DATA_MB} MB userdata partition for Codespaces validation."
  : > "$EMULATOR_LOG"
  nohup "$EMULATOR" \
    -avd "$AVD_NAME" \
    -port "$EMULATOR_PORT" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -no-snapshot-load \
    -no-snapshot-save \
    -wipe-data \
    -partition-size "$EMULATOR_DATA_MB" \
    -memory "$EMULATOR_MEMORY_MB" \
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
