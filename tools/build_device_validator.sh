#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

EXPECTED_JAVA_MAJOR="17"
DIST_DIR="$ROOT_DIR/dist"
APK_SOURCE="$ROOT_DIR/device-validator/build/outputs/apk/debug/device-validator-debug.apk"
APK_DEST="$DIST_DIR/ETroute-Device-Validator-debug.apk"
mkdir -p "$DIST_DIR"

select_jdk17() {
  local candidates=()
  local candidate major

  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
  for candidate in /usr/lib/jvm/java-17-openjdk-* /usr/lib/jvm/*17* /usr/local/sdkman/candidates/java/17*; do
    [[ -d "$candidate" ]] && candidates+=("$candidate")
  done

  for candidate in "${candidates[@]}"; do
    [[ -x "$candidate/bin/java" ]] || continue
    major="$("$candidate/bin/java" -version 2>&1 | head -n1 | sed -E 's/.*version "([0-9]+).*/\1/')"
    if [[ "$major" == "$EXPECTED_JAVA_MAJOR" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      echo "[ETroute] JDK 17: $JAVA_HOME"
      return 0
    fi
  done

  echo "[ETroute] ERROR: JDK 17 not found." >&2
  exit 2
}

select_jdk17

if [[ ! -x ./gradlew ]]; then
  echo "[ETroute] ERROR: Gradle wrapper missing. Run tools/run_android_jni_validation.sh once to bootstrap it." >&2
  exit 2
fi

if [[ ! -f local.properties ]]; then
  echo "[ETroute] ERROR: local.properties missing. Run tools/bootstrap_android_sdk.sh first." >&2
  exit 2
fi

echo "[ETroute] Building ETroute library + physical-device validator APK."
./gradlew --no-daemon --console=plain \
  :android:assembleDebug \
  :device-validator:assembleDebug

if [[ ! -f "$APK_SOURCE" ]]; then
  echo "[ETroute] ERROR: expected APK not found: $APK_SOURCE" >&2
  exit 3
fi

cp "$APK_SOURCE" "$APK_DEST"

if command -v unzip >/dev/null 2>&1; then
  echo "[ETroute] Verifying packaged JNI libraries."
  LISTING="$(unzip -l "$APK_DEST")"
  grep -Fq "lib/arm64-v8a/libetroute_native_supervisor.so" <<<"$LISTING" || {
    echo "[ETroute] ERROR: arm64-v8a JNI library missing from APK." >&2
    exit 4
  }
  grep -Fq "lib/x86_64/libetroute_native_supervisor.so" <<<"$LISTING" || {
    echo "[ETroute] ERROR: x86_64 JNI library missing from APK." >&2
    exit 4
  }
fi

SHA256="$(sha256sum "$APK_DEST" | awk '{print $1}')"
SIZE="$(stat -c %s "$APK_DEST")"

cat <<EOF
[ETroute] DEVICE VALIDATOR APK READY
path=$APK_DEST
bytes=$SIZE
sha256=$SHA256

Final physical-device test:
1. Install ETroute-Device-Validator-debug.apk on Android.
2. Open ETroute Validator.
3. Tap RUN ETROUTE TEST.
4. Copy the report and send it back if any step fails.
EOF
