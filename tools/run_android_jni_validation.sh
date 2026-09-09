#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -x "./gradlew" ]]; then
  GRADLE=("./gradlew")
elif command -v gradle >/dev/null 2>&1; then
  echo "[ETroute] Gradle wrapper not found; generating Gradle 8.7 wrapper locally."
  gradle wrapper --gradle-version 8.7
  GRADLE=("./gradlew")
else
  echo "[ETroute] ERROR: neither ./gradlew nor gradle is available." >&2
  exit 2
fi

echo "[ETroute] Building Android JNI validation module (arm64-v8a + x86_64)."
"${GRADLE[@]}" :android:assembleDebug :android:assembleDebugAndroidTest

echo "[ETroute] Verifying NDK build outputs and JNI exports."
python3 tools/verify_android_jni_build.py

if ! command -v adb >/dev/null 2>&1; then
  echo "[ETroute] Build/link verification passed, but adb is not available."
  echo "[ETroute] Status may advance to JNI_NDK_BUILD_VERIFIED only."
  echo "[ETroute] JNI remains DEVICE_UNVALIDATED."
  exit 3
fi

echo "[ETroute] Connected Android targets:"
adb devices -l

mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2 == "device" {print $1}')
if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "[ETroute] Build/link verification passed, but no ready device/emulator is connected."
  echo "[ETroute] JNI remains DEVICE_UNVALIDATED."
  exit 3
fi

validated=0
for serial in "${DEVICES[@]}"; do
  abi="$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "[ETroute] target=$serial abi=$abi"

  case "$abi" in
    arm64-v8a|x86_64)
      ANDROID_SERIAL="$serial" "${GRADLE[@]}" :android:connectedDebugAndroidTest
      validated=$((validated + 1))
      ;;
    *)
      echo "[ETroute] Skipping unsupported validation ABI: $abi"
      ;;
  esac
done

if [[ $validated -eq 0 ]]; then
  echo "[ETroute] No supported arm64-v8a/x86_64 target was validated." >&2
  exit 3
fi

echo "[ETroute] JNI instrumentation run complete."
echo "[ETroute] Review android/build/reports/androidTests/connected/ before marking JNI_GREEN."
