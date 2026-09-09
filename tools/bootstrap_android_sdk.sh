#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/.android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-15859902_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
CMDLINE_TOOLS_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [[ "${ETROUTE_ACCEPT_ANDROID_SDK_LICENSES:-0}" != "1" ]]; then
  cat >&2 <<'EOF'
[ETroute] Android SDK installation requires accepting Google's Android SDK license terms.
[ETroute] Review: https://developer.android.com/studio
[ETroute] If you accept those terms, rerun with:

  ETROUTE_ACCEPT_ANDROID_SDK_LICENSES=1 bash tools/bootstrap_android_sdk.sh

[ETroute] No license terms were accepted automatically.
EOF
  exit 4
fi

for tool in curl unzip sha256sum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "[ETroute] ERROR: required tool missing: $tool" >&2
    exit 2
  fi
done

mkdir -p "$SDK_ROOT/cmdline-tools"

if [[ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "[ETroute] Downloading Android command-line tools."
  curl -fL "$CMDLINE_TOOLS_URL" -o "$TMP_DIR/$CMDLINE_TOOLS_ZIP"
  echo "$CMDLINE_TOOLS_SHA256  $TMP_DIR/$CMDLINE_TOOLS_ZIP" | sha256sum -c -

  unzip -q "$TMP_DIR/$CMDLINE_TOOLS_ZIP" -d "$TMP_DIR/unpacked"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$SDK_ROOT/cmdline-tools/latest"
  cp -R "$TMP_DIR/unpacked/cmdline-tools/." "$SDK_ROOT/cmdline-tools/latest/"
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

accept_sdk_licenses() {
  echo "[ETroute] Accepting Android SDK component licenses as explicitly authorized."

  # Under `set -o pipefail`, `yes | sdkmanager --licenses` can appear to fail
  # even when sdkmanager succeeds: once sdkmanager has consumed all required
  # answers and exits, `yes` receives SIGPIPE (typically exit 141). Capture the
  # sdkmanager status itself rather than treating the producer's SIGPIPE as an
  # SDK failure.
  set +o pipefail
  yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses
  local statuses=("${PIPESTATUS[@]}")
  set -o pipefail

  local sdk_status="${statuses[1]:-1}"
  if [[ "$sdk_status" -ne 0 ]]; then
    echo "[ETroute] ERROR: Android SDK license acceptance failed (sdkmanager=$sdk_status)." >&2
    exit "$sdk_status"
  fi

  echo "[ETroute] Android SDK licenses accepted."
}

verify_required_components() {
  local missing=0

  check_path() {
    local label="$1"
    local path="$2"
    if [[ -e "$path" ]]; then
      echo "[ETroute] verified: $label"
    else
      echo "[ETroute] MISSING: $label ($path)" >&2
      missing=1
    fi
  }

  check_path "platform-tools/adb" "$SDK_ROOT/platform-tools/adb"
  check_path "Android API 36 platform" "$SDK_ROOT/platforms/android-36/android.jar"
  check_path "Build Tools 35.0.0" "$SDK_ROOT/build-tools/35.0.0/aapt2"
  check_path "NDK 27.0.12077973" "$SDK_ROOT/ndk/27.0.12077973/source.properties"
  check_path "CMake 3.22.1" "$SDK_ROOT/cmake/3.22.1/bin/cmake"

  if [[ "$missing" -ne 0 ]]; then
    echo "[ETroute] ERROR: Android SDK bootstrap finished with missing required components." >&2
    exit 5
  fi
}

accept_sdk_licenses

echo "[ETroute] Installing required ETroute Android toolchain packages."
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;35.0.0" \
  "ndk;27.0.12077973" \
  "cmake;3.22.1"

echo "[ETroute] Verifying installed Android toolchain components."
verify_required_components

cat > "$ROOT_DIR/local.properties" <<EOF
sdk.dir=$SDK_ROOT
EOF

cat <<EOF
[ETroute] Android SDK bootstrap complete.
[ETroute] SDK root: $SDK_ROOT
[ETroute] local.properties updated.
[ETroute] Note: sdkmanager currently emits a deprecation warning in favor of Android CLI;
[ETroute] this bootstrap remains compatible while using the official command-line-tools package.
[ETroute] Next:
  bash tools/run_android_jni_validation.sh
EOF
