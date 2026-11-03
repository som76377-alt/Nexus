#!/usr/bin/env bash
set -euo pipefail

GRADLE_VERSION="9.6.0"
ANDROID_TOOLS_REVISION="15859902"
GRADLE_ROOT="$HOME/.local/gradle"
SDK_ROOT="$HOME/Android/Sdk"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y --no-install-recommends curl unzip
fi

mkdir -p "$GRADLE_ROOT" "$SDK_ROOT/cmdline-tools"

if [[ ! -x "$GRADLE_ROOT/gradle-$GRADLE_VERSION/bin/gradle" ]]; then
  curl --fail --location --retry 3 \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
    --output "$TMP_DIR/gradle.zip"
  curl --fail --location --retry 3 \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip.sha256" \
    --output "$TMP_DIR/gradle.zip.sha256"
  expected="$(tr -d '[:space:]' < "$TMP_DIR/gradle.zip.sha256")"
  printf '%s  %s\n' "$expected" "$TMP_DIR/gradle.zip" | sha256sum --check -
  unzip -q "$TMP_DIR/gradle.zip" -d "$GRADLE_ROOT"
fi

if [[ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  curl --fail --location --retry 3 \
    "https://dl.google.com/android/repository/commandlinetools-linux-$ANDROID_TOOLS_REVISION""_latest.zip" \
    --output "$TMP_DIR/android-tools.zip"
  mkdir -p "$SDK_ROOT/cmdline-tools/latest"
  unzip -q "$TMP_DIR/android-tools.zip" -d "$TMP_DIR/android-tools"
  mv "$TMP_DIR/android-tools/cmdline-tools/"* "$SDK_ROOT/cmdline-tools/latest/"
fi

echo
echo "Nexus tool bootstrap complete."
echo "Gradle: $GRADLE_ROOT/gradle-$GRADLE_VERSION/bin/gradle"
echo "Android SDK tools: $SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
echo
echo "Next, review and accept the Android SDK licence, then install the SDK packages:"
echo "  sdkmanager --licenses"
echo "  sdkmanager \"platform-tools\" \"platforms;android-36\" \"build-tools;36.0.0\""
