#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

EXPECTED_GRADLE_VERSION="8.13"
EXPECTED_JAVA_MAJOR="17"
EVIDENCE_DIR="$ROOT_DIR/evidence/android_jni/latest"
BUILD_LOG="$EVIDENCE_DIR/gradle-build.log"
WRAPPER_LOG="$EVIDENCE_DIR/gradle-wrapper-bootstrap.log"
ENV_LOG="$EVIDENCE_DIR/build-environment.txt"
mkdir -p "$EVIDENCE_DIR"

java_major_for_home() {
  local home="$1"
  [[ -x "$home/bin/java" ]] || return 1
  "$home/bin/java" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/'
}

select_jdk17() {
  local candidates=()
  local candidate major seen="|"

  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
  for candidate in \
    /usr/lib/jvm/java-17-openjdk-* \
    /usr/lib/jvm/*17* \
    /usr/local/sdkman/candidates/java/17* \
    /opt/java/17*; do
    [[ -d "$candidate" ]] && candidates+=("$candidate")
  done

  if command -v update-java-alternatives >/dev/null 2>&1; then
    while read -r candidate; do
      [[ -d "$candidate" ]] && candidates+=("$candidate")
    done < <(update-java-alternatives -l 2>/dev/null | awk '$1 ~ /17/ {print $3}')
  fi

  for candidate in "${candidates[@]}"; do
    [[ "$seen" == *"|$candidate|"* ]] && continue
    seen+="$candidate|"
    major="$(java_major_for_home "$candidate" 2>/dev/null || true)"
    if [[ "$major" == "$EXPECTED_JAVA_MAJOR" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      echo "[ETroute] Selected JDK 17: $JAVA_HOME"
      return 0
    fi
  done

  echo "[ETroute] ERROR: JDK 17 is required." >&2
  echo "  sudo apt-get update && sudo apt-get install -y openjdk-17-jdk" >&2
  exit 2
}

is_android_sdk() {
  local root="$1"
  [[ -d "$root" ]] || return 1
  [[ -d "$root/platforms" || -x "$root/cmdline-tools/latest/bin/sdkmanager" || -x "$root/platform-tools/adb" ]]
}

select_android_sdk() {
  local candidates=()
  local candidate seen="|"

  [[ -n "${ANDROID_SDK_ROOT:-}" ]] && candidates+=("$ANDROID_SDK_ROOT")
  [[ -n "${ANDROID_HOME:-}" ]] && candidates+=("$ANDROID_HOME")
  candidates+=(
    "$HOME/.android-sdk"
    "$HOME/Android/Sdk"
    "/usr/local/lib/android/sdk"
    "/usr/local/android-sdk"
    "/opt/android-sdk"
    "/opt/android-sdk-linux"
  )

  if [[ -f "$ROOT_DIR/local.properties" ]]; then
    candidate="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | head -n 1)"
    [[ -n "$candidate" ]] && candidates=("$candidate" "${candidates[@]}")
  fi

  for candidate in "${candidates[@]}"; do
    [[ "$seen" == *"|$candidate|"* ]] && continue
    seen+="$candidate|"
    if is_android_sdk "$candidate"; then
      export ANDROID_HOME="$candidate"
      export ANDROID_SDK_ROOT="$candidate"
      export PATH="$candidate/cmdline-tools/latest/bin:$candidate/platform-tools:$PATH"
      printf 'sdk.dir=%s\n' "$candidate" > "$ROOT_DIR/local.properties"
      echo "[ETroute] Selected Android SDK: $candidate"
      return 0
    fi
  done

  cat >&2 <<'EOF'
[ETroute] ERROR: Android SDK location not found.
[ETroute] ETroute now includes a one-time Codespaces bootstrap helper.
[ETroute] Review Google's Android SDK license terms first, then if you accept them run:

  ETROUTE_ACCEPT_ANDROID_SDK_LICENSES=1 bash tools/bootstrap_android_sdk.sh

[ETroute] After setup, rerun:
  bash tools/run_android_jni_validation.sh
EOF
  exit 4
}

print_build_failure_summary() {
  local log_file="$1"
  echo
  echo "[ETroute] ===== focused Gradle failure summary =====" >&2
  grep -nEi \
    'FAILURE: Build failed|What went wrong|Execution failed|error:|CMake Error|SDK location|Android SDK|NDK|Could not resolve|requires Gradle|Minimum supported Gradle|Maximum supported Gradle|Android Gradle plugin requires Java|Unsupported class file|Could not determine|Plugin .* was not found|Unsupported Java|Java version' \
    "$log_file" | tail -n 100 >&2 || true
  echo "[ETroute] ===== tail of full Gradle log =====" >&2
  tail -n 140 "$log_file" >&2 || true
  echo "[ETroute] Full log: $log_file" >&2
}

bootstrap_gradle_wrapper() {
  if ! command -v gradle >/dev/null 2>&1; then
    echo "[ETroute] ERROR: Gradle wrapper is unavailable and system Gradle is not installed." >&2
    exit 2
  fi

  local bootstrap_dir bootstrap_status
  bootstrap_dir="$(mktemp -d)"
  cat > "$bootstrap_dir/settings.gradle" <<'EOF'
rootProject.name = 'etroute-wrapper-bootstrap'
EOF
  cat > "$bootstrap_dir/build.gradle" <<EOF
wrapper {
    gradleVersion = '$EXPECTED_GRADLE_VERSION'
    distributionType = Wrapper.DistributionType.BIN
}
EOF

  echo "[ETroute] Bootstrapping Gradle $EXPECTED_GRADLE_VERSION wrapper in an isolated temporary build."
  set +e
  (
    cd "$bootstrap_dir"
    gradle --no-daemon --console=plain wrapper \
      --gradle-version "$EXPECTED_GRADLE_VERSION" \
      --distribution-type bin
  ) 2>&1 | tee "$WRAPPER_LOG"
  bootstrap_status=${PIPESTATUS[0]}
  set -e

  if [[ $bootstrap_status -ne 0 ]]; then
    print_build_failure_summary "$WRAPPER_LOG"
    rm -rf "$bootstrap_dir"
    exit "$bootstrap_status"
  fi

  cp "$bootstrap_dir/gradlew" "$ROOT_DIR/gradlew"
  cp "$bootstrap_dir/gradlew.bat" "$ROOT_DIR/gradlew.bat"
  rm -rf "$ROOT_DIR/gradle/wrapper"
  mkdir -p "$ROOT_DIR/gradle"
  cp -R "$bootstrap_dir/gradle/wrapper" "$ROOT_DIR/gradle/"
  chmod +x "$ROOT_DIR/gradlew"
  rm -rf "$bootstrap_dir"
}

wrapper_is_expected_version() {
  local properties="$ROOT_DIR/gradle/wrapper/gradle-wrapper.properties"
  [[ -x "$ROOT_DIR/gradlew" ]] || return 1
  [[ -f "$properties" ]] || return 1
  grep -q "gradle-${EXPECTED_GRADLE_VERSION}-bin.zip" "$properties"
}

select_jdk17
select_android_sdk

{
  echo "JAVA_HOME=$JAVA_HOME"
  echo "ANDROID_HOME=$ANDROID_HOME"
  echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
  echo "PATH=$PATH"
  echo
  java -version 2>&1
} > "$ENV_LOG"

if ! wrapper_is_expected_version; then
  echo "[ETroute] A compatible Gradle wrapper was not found."
  bootstrap_gradle_wrapper
fi

GRADLE=("./gradlew")

echo "[ETroute] Verifying Gradle wrapper and JVM."
GRADLE_VERSION_OUTPUT="$("${GRADLE[@]}" --version --console=plain 2>&1)"
echo "$GRADLE_VERSION_OUTPUT" | sed -n '1,14p'
if ! grep -q "Gradle $EXPECTED_GRADLE_VERSION" <<<"$GRADLE_VERSION_OUTPUT"; then
  echo "[ETroute] ERROR: expected Gradle $EXPECTED_GRADLE_VERSION wrapper." >&2
  exit 2
fi
if ! grep -Eq 'JVM: +17([. ]|$)' <<<"$GRADLE_VERSION_OUTPUT"; then
  echo "[ETroute] ERROR: Gradle is not running on JDK 17." >&2
  exit 2
fi

echo "[ETroute] Building Android JNI validation module (arm64-v8a + x86_64)."
set +e
"${GRADLE[@]}" --no-daemon --console=plain --stacktrace \
  :android:assembleDebug :android:assembleDebugAndroidTest \
  2>&1 | tee "$BUILD_LOG"
BUILD_STATUS=${PIPESTATUS[0]}
set -e

if [[ $BUILD_STATUS -ne 0 ]]; then
  echo "[ETroute] Android JNI Gradle build failed." >&2
  print_build_failure_summary "$BUILD_LOG"
  echo "[ETroute] JNI status remains ANDROID_UNVALIDATED." >&2
  exit "$BUILD_STATUS"
fi

echo "[ETroute] Verifying NDK build outputs and JNI exports."
python3 tools/verify_android_jni_build.py

if [[ "${ETROUTE_BUILD_ONLY:-0}" == "1" ]]; then
  echo "[ETroute] JNI_NDK_BUILD_VERIFIED"
  echo "[ETroute] Build-only mode complete; physical-device runtime validation intentionally deferred."
  exit 0
fi

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
      device_log="$EVIDENCE_DIR/instrumentation-${serial//[^A-Za-z0-9_.-]/_}.log"
      set +e
      ANDROID_SERIAL="$serial" "${GRADLE[@]}" --no-daemon --console=plain \
        :android:connectedDebugAndroidTest 2>&1 | tee "$device_log"
      TEST_STATUS=${PIPESTATUS[0]}
      set -e
      if [[ $TEST_STATUS -ne 0 ]]; then
        print_build_failure_summary "$device_log"
        exit "$TEST_STATUS"
      fi
      validated=$((validated + 1))
      ;;
    *) echo "[ETroute] Skipping unsupported validation ABI: $abi" ;;
  esac
done

if [[ $validated -eq 0 ]]; then
  echo "[ETroute] No supported arm64-v8a/x86_64 target was validated." >&2
  exit 3
fi

echo "[ETroute] JNI instrumentation run complete."
echo "[ETroute] Review android/build/reports/androidTests/connected/ before marking JNI_GREEN."
