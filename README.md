# SignON

Morning HRV and rHR.

SignON records a 1-minute morning measurement from your Bluetooth chest
strap and writes your resting heart rate and heart rate variability
(rMSSD and SDNN) to today's wellness record in your own Intervals.icu
account. It is deliberately simple: no charts, trends or readiness
scores, no account, no server, no tracking. Your values go from your
phone to Intervals.icu and nowhere else. You need a chest strap that
sends beat-to-beat (RR) data and an Intervals.icu API key (Intervals.icu,
Settings, Developer Settings).

SignON is not a medical device.

## Install on Android

Android 8.0 or newer, with Bluetooth.

1. Open the latest release under
   [Releases](https://github.com/afternoonwork/SignON/releases) and
   download `SignON-<version>.apk` and `SignON-<version>.apk.sha256`.
2. Optional but recommended, on a computer: check the file.
   - SHA-256 of the APK must match the `.sha256` file and the release
     notes: `shasum -a 256 SignON-<version>.apk` (macOS),
     `sha256sum SignON-<version>.apk` (Linux) or
     `Get-FileHash SignON-<version>.apk` (Windows PowerShell).
   - The signing certificate SHA-256 must match the release notes:
     `apksigner verify --print-certs SignON-<version>.apk` (Android SDK
     build tools). It is the same for every release.
3. Open the APK on your phone. When Android asks, allow your browser or
   file manager to install unknown apps (Settings, Apps, Special app
   access, Install unknown apps), then tap Install. If Play Protect warns
   about an unknown app, choose to install anyway.
4. Updates: install the newer APK over the old one. Your measurements and
   API key stay.

## Beta

The Android app is a beta and has not yet run on any Android phone:
Bluetooth and the secure storage of your API key have only been tested in
simulation. So the first users are its testers. If connecting or measuring
fails, open Settings in the app, tap Debug log, then Share, and send it by
email to signon(at)paulmuller.fyi. The log contains your heart data for that session
(but never your API key), so please do not post it in a public GitHub
issue.

## iOS

Coming later.

## Contact

signon(at)paulmuller.fyi

## Privacy

See the [privacy policy](docs/privacy-policy.md).

## Copyright

Copyright Paul Müller. All rights reserved. The source code is public for
transparency; no licence is granted.
