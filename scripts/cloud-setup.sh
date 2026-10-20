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

// Robolectric fetches android-all jars itself at test time.
allprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
GRADLE

echo "Android SDK installed at $ANDROID_HOME"

# Swift toolchain for the ios/HRVCore tests on Linux (Swift 6.2.x, like Xcode 26.x).
# Needs download.swift.org allowed. If it is blocked, fall back to the official
# swift Docker image (Docker Hub is reachable by default); scripts/swift-test.sh
# uses whichever is available.
SWIFT_VERSION=6.2.4
SWIFT_HOME="$HOME/swift-$SWIFT_VERSION"
if [ ! -x "$SWIFT_HOME/usr/bin/swift" ]; then
  SWIFT_URL="https://download.swift.org/swift-$SWIFT_VERSION-release/ubuntu2404/swift-$SWIFT_VERSION-RELEASE/swift-$SWIFT_VERSION-RELEASE-ubuntu24.04.tar.gz"
  if curl -fsSL -o /tmp/swift.tar.gz "$SWIFT_URL"; then
    apt-get install -y -qq binutils libc6-dev libcurl4-openssl-dev libedit2 libgcc-13-dev libncurses-dev \
      libpython3-dev libsqlite3-0 libstdc++-13-dev libxml2-dev libz3-dev pkg-config tzdata zlib1g-dev > /dev/null || true
    mkdir -p "$SWIFT_HOME"
    tar -xzf /tmp/swift.tar.gz --strip-components=1 -C "$SWIFT_HOME"
    rm /tmp/swift.tar.gz
    echo "export PATH=$SWIFT_HOME/usr/bin:\$PATH" >> "$HOME/.bashrc"
    echo "Swift $SWIFT_VERSION installed at $SWIFT_HOME"
  elif command -v docker > /dev/null; then
    echo "download.swift.org not reachable; using Docker image swift:6.2-noble instead"
    if ! docker info > /dev/null 2>&1; then (dockerd > /tmp/dockerd.log 2>&1 &); sleep 5; fi
    docker pull -q swift:6.2-noble > /dev/null || echo "docker pull swift:6.2-noble failed"
  else
    echo "No Swift toolchain: allow download.swift.org for this environment"
  fi
fi
