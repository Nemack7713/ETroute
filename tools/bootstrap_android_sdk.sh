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

echo "[ETroute] Accepting Android SDK component licenses as explicitly authorized."
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null

echo "[ETroute] Installing required ETroute Android toolchain packages."
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;35.0.0" \
  "ndk;27.0.12077973" \
  "cmake;3.22.1"

cat > "$ROOT_DIR/local.properties" <<EOF
sdk.dir=$SDK_ROOT
EOF

cat <<EOF
[ETroute] Android SDK bootstrap complete.
[ETroute] SDK root: $SDK_ROOT
[ETroute] local.properties updated.
[ETroute] Next:
  bash tools/run_android_jni_validation.sh
EOF
