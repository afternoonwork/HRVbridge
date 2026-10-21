#!/bin/bash
# Screenshots of every main screen state, the dial, connect and settings, in light
# and dark, on an iPhone SE simulator (375 x 667 points). Debug builds only: the app
# shows one fixed state when launched with -uiSnapshot <name> (Snapshots.swift).
# Usage: scripts/ios-snapshots.sh <path to HRVBridge.app> <output dir>
set -euo pipefail
APP="$1"
OUT="$2"
mkdir -p "$OUT"

STATES="dial connect connect-rejected connect-connected settings
no-strap searching connecting connected retry cancelled could-not-connect bluetooth-off not-allowed
poor-contact settling measuring failed-no-rr failed-disconnected
uploading uploaded unreliable not-enough-beats upload-failed not-connected questions questions-answered"

BUNDLE=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")
RUNTIME=$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
r = [x for x in json.load(sys.stdin)["runtimes"] if x["isAvailable"] and x["platform"] == "iOS"]
print(r[-1]["identifier"])')
TYPE=$(xcrun simctl list devicetypes -j | python3 -c '
import json, sys
t = [x["identifier"] for x in json.load(sys.stdin)["devicetypes"] if "iPhone-SE" in x["identifier"]]
print(sorted(t)[-1])')
echo "Runtime $RUNTIME, device type $TYPE"
DEVICE=$(xcrun simctl create hrv-snapshots "$TYPE" "$RUNTIME")
trap 'xcrun simctl shutdown "$DEVICE" || true; xcrun simctl delete "$DEVICE" || true' EXIT
xcrun simctl boot "$DEVICE"
xcrun simctl bootstatus "$DEVICE" -b > /dev/null
xcrun simctl status_bar "$DEVICE" override --time 9:41 --batteryState charged --batteryLevel 100 \
  --cellularMode active --cellularBars 4 --wifiBars 3 || true
xcrun simctl install "$DEVICE" "$APP"

for appearance in light dark; do
  xcrun simctl ui "$DEVICE" appearance "$appearance"
  for s in $STATES; do
    xcrun simctl launch --terminate-running-process "$DEVICE" "$BUNDLE" -uiSnapshot "$s" > /dev/null
    sleep 2
    xcrun simctl io "$DEVICE" screenshot "$OUT/$s-$appearance.png" > /dev/null 2>&1
    echo "$s-$appearance.png"
  done
done
