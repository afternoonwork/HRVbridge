#!/bin/bash
# Runs the ios/HRVCore tests: native swift if installed (see cloud-setup.sh),
# otherwise in the swift:6.2 Docker image. Extra arguments go to `swift test`.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SWIFT_BIN="$(ls -d "$HOME"/swift-*/usr/bin 2>/dev/null | tail -1 || true)"
if command -v swift > /dev/null || [ -n "$SWIFT_BIN" ]; then
  PATH="${SWIFT_BIN:+$SWIFT_BIN:}$PATH"
  cd "$REPO/ios/HRVCore" && exec swift test "$@"
fi
if ! docker info > /dev/null 2>&1; then (dockerd > /tmp/dockerd.log 2>&1 &); sleep 5; fi
exec docker run --rm -v "$REPO:/work" -v hrvcore-build:/work/ios/HRVCore/.build \
  -w /work/ios/HRVCore swift:6.2-noble swift test "$@"
