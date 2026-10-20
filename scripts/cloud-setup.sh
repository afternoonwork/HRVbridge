#!/bin/bash
# Setup script for the Claude Code cloud environment (paste into the
# environment's "Setup script" field). Installs the Android SDK on top of the
# base image, which already has OpenJDK 21 and Gradle.
#
# Network: the environment needs the Trusted defaults PLUS dl.google.com
# (Android SDK downloads). Use "Custom" access, include the defaults, and add:
#   dl.google.com
#
# The cmdline-tools build number below may be outdated; if the download 404s,
# check https://developer.android.com/studio#command-line-tools-only
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_ZIP="commandlinetools-linux-13114758_latest.zip"

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  cd /tmp
  curl -fsSL -o cmdline-tools.zip "https://dl.google.com/android/repository/$CMDLINE_ZIP"
  if command -v unzip > /dev/null; then
    unzip -q -o cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
  else
    python3 -m zipfile -e cmdline-tools.zip "$ANDROID_HOME/cmdline-tools"
    chmod +x "$ANDROID_HOME"/cmdline-tools/cmdline-tools/bin/*
  fi
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm cmdline-tools.zip
fi

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
# "yes" is killed with SIGPIPE (exit 141) once sdkmanager stops reading;
# with pipefail that would fail the script, so ignore the pipeline status.
(yes || true) | sdkmanager --licenses > /dev/null || true
sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0" > /dev/null

# Make the SDK location visible to later shells and to Gradle.
{
  echo "export ANDROID_HOME=$ANDROID_HOME"
  echo "export ANDROID_SDK_ROOT=$ANDROID_HOME"
  echo "export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:\$PATH"
} >> "$HOME/.bashrc"

# Maven Central often answers 429 to the shared cloud egress IP. Gradle tries
# Google's public Maven Central mirror first (this machine only, not the build).
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/maven-central-mirror.gradle.kts" <<'GRADLE'
beforeSettings {
    val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
    pluginManagement.repositories { maven(mirror) }
    dependencyResolutionManagement.repositories { maven(mirror) }
}
GRADLE

echo "Android SDK installed at $ANDROID_HOME"
