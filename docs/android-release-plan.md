# SignON: Android release plan (APK on GitHub Releases)

Written 21 Oct 2026 (day 0). App: SignON, applicationId
`fyi.paulmuller.signon`, free, no ads, no in-app purchases, donation link
outside the app.

## Decision (Paul, 21 Oct 2026)

No Google Play for now. Paul has no Android phone and knows no Android
users, so two Play rules rule it out: new personal developer accounts
must verify a physical Android phone, and must run a closed test with 12
testers for 14 days before production. Instead, Android ships as a signed
APK attached to GitHub Releases of this repo. iOS comes later. F-Droid is
out: it only accepts apps under a free software licence, and this repo
deliberately has none.

How a release works (`.github/workflows/android-release.yml`, PR #42):
push a tag `v<versionName>`; CI runs the tests, builds and signs the
release APK, checks it with `apksigner`, and creates a GitHub Release with
`SignON-<versionName>.apk`, its `.sha256` file and the signing certificate
SHA-256 in the notes. Tags with `-beta` become prereleases. Install steps
for users are in `README.md`.

## 1. Blockers

| # | Blocker | Owner | Why |
|---|---------|-------|-----|
| B1 | Release workflow pushed | Paul | The gh token lacks the `workflow` scope, so the workflow commit of PR #42 could not be pushed. Fix: `gh auth refresh -h github.com -s workflow`, then push the branch. |
| B2 | Signing key and four GitHub secrets | Paul | Without them the release workflow fails on purpose (no unsigned releases). Steps in section 2. |
| B3 | Launcher icon | Agent (PR #41), Paul approves | Without it the app shows the default Android icon. |
| B4 | Privacy policy final text | Agent drafts, Paul fills contact | `docs/privacy-policy.md` still has `[date]`, `[URL]` and `[Name], [email address]`. The README links to it; the repo copy is enough as the URL, no separate hosting needed for the APK route. |
| B5 | First real test on an Android phone with a strap | Paul finds a tester | BLE and the Keystore have never run on a device. Until then every release is a beta and its first users are the testers. |

Not blockers (checked):

- **OAuth.** The Intervals.icu API terms allow use "for any lawful
  purpose"; OAuth is recommended for apps with many users, not required.
  Personal API keys stay for v1.
- **Developer verification.** Not enforced in Paul's market before 2027
  (section 3).
- **R8.** Off for now (`shortcut:` comment in `app/build.gradle.kts`);
  switch on once a release APK has been smoke tested on a phone.

## 2. Ordered steps

"P" = Paul by hand, "A" = agent PR. Dates are targets from day 0.

| When | Step | Who |
|------|------|-----|
| 21 Oct | `gh auth refresh -h github.com -s workflow`, push PR #42's workflow commit, wait for CI (lint now runs on every push). | P, then A fixes lint |
| 21 to 23 Oct | Review and merge #41 (icon), #42 (release), #40 (this plan). | P (reviewer agent comments) |
| 21 to 23 Oct | Create the signing key once: `keytool -genkeypair -v -keystore ~/keys/signon-upload.jks -alias signon -keyalg RSA -keysize 4096 -validity 10000` | P |
| same day | Store it as secrets (each command prompts, nothing in shell history): `base64 -i ~/keys/signon-upload.jks \| gh secret set HRVBRIDGE_KEYSTORE_BASE64 -R mularmularmular/HRVbridge`, then `gh secret set HRVBRIDGE_KEYSTORE_PASSWORD`, `HRVBRIDGE_KEY_ALIAS` (`signon`) and `HRVBRIDGE_KEY_PASSWORD`, each with `-R mularmularmular/HRVbridge`. | P |
| same day | Back up the .jks and both passwords twice, offline, in two places. Losing the key means users can never update; there is no reset outside Play. | P |
| by 24 Oct | Privacy policy final text: effective date, contact, URL of the file on GitHub. | A, Paul fills contact |
| by 24 Oct | `git tag v0.1.0-beta.1 && git push origin v0.1.0-beta.1`. Check the release page: APK, `.sha256`, certificate fingerprint in the notes. | P |
| from 24 Oct | Find a first tester with an Android phone and a chest strap (training partners, then a post in the Intervals.icu forum). Ask them to install from Releases, measure, upload, and send the debug log (never posted in the repo or issues). | P |
| as reports come in | Fix what the device tests find (BLE scan and GATT, permission flow, Keystore). Each fix: bump `versionName` and `versionCode`, tag, release. | A, then P tags |
| after a good device test | Turn on R8 (`isMinifyEnabled`, `isShrinkResources`), release a beta with it, ask the tester to smoke test. | A |
| when stable | First non-beta release, e.g. `v0.1.0` with `versionCode` 2 or higher. | P |

Versioning: the tag must equal `versionName` (the workflow checks it).
`versionCode` goes up by 1 for every release, because Android refuses to
install an APK with a lower `versionCode` over a higher one.

## 3. Developer verification timeline

Google requires a verified developer for apps installed outside Play, in
stages (checked 21 Oct 2026):

- From 30 Sep 2026: enforced only in Brazil, Indonesia, Singapore and
  Thailand. Users there cannot install SignON from an unverified
  developer without the "advanced flow".
- 2027: enforced globally on certified Android phones.
- The "advanced flow" lets users still install apps from unverified
  developers, but with extra steps and warnings. Fine for a handful of
  testers, not for a public release.

What to do, in 2027 or earlier once Android users exist:

1. Create an Android Developer Console account (personal), verify
   identity. Expect a fee and the same ID checks as Play.
2. Register the package `fyi.paulmuller.signon` and prove ownership with
   the signing key (same key as on GitHub Releases, so existing installs
   keep updating).
3. Update this doc and `README.md` once the steps are known in detail.
4. Watch the Android Developers Blog; the global date may move.

## 4. Risks

- **Untested on any device.** BLE (`BleHeartRateSource`) and the Keystore
  credential path have only run in Robolectric and the simulated strap.
  The first users are testers; the README says so and asks for the debug
  log.
- **Play Protect warnings.** An APK from an unknown developer may trigger
  "unknown app" or "scan app" warnings, and from 2027 refusals without
  verification. README tells users what to expect.
- **Key loss.** No Play App Signing, so the key cannot be reset. Two
  offline backups, never in the repo or chat.
- **Key leak.** Anyone with the key and passwords could ship an update
  that installs over SignON. The key lives only in Paul's backups and in
  GitHub secrets, and the workflow deletes its temporary copy.
- **No automatic updates.** Users must check Releases themselves. Accept
  for the beta; reconsider Play when there are users.
- **Moving to Play later.** Play can keep using this key (upload it as the
  app signing key during Play App Signing setup), so sideloaded users can
  move to the Play build without uninstalling. Choose "use my own key"
  then, not a Google-generated one.

## 5. Google Play later

Findings checked 21 Oct 2026, kept for when Play becomes worth it:

- Developer account: one-time 25 USD, government ID, card in Paul's legal
  name, email and phone verified by one-time password.
- New personal accounts must verify access to a **physical, non-rooted
  Android phone running Android 10 or newer** through the Play Console
  mobile app before publishing anything.
- Before production: a **closed test with at least 12 testers opted in
  for at least 14 consecutive days**, then an application from the
  Dashboard, usually reviewed within seven days.
- Target API 36 is required since 31 Aug 2026; we target 36. Expect API 37
  around Aug 2027. Play does not require compileSdk 37, so the Compose BOM
  pin can stay.
- Data safety: **no data collected or shared** (Paul's decision), relying
  on Google's exception for uploads to the user's own account with another
  provider. Fallback if review objects: "Health and fitness" collected,
  not shared, encrypted in transit, required for the app's function.
- Health apps declaration: "Activity and Fitness" only, not "Medical
  device". No Health Connect, so no Health Connect declaration.
- No permissions declaration form needed (`neverForLocation` on
  `BLUETOOTH_SCAN`, location `maxSdkVersion 30`).
- Listing needs a 512x512 icon, a 1024x500 feature graphic, at least two
  phone screenshots, a public contact email and a privacy policy URL.
  Other answers are in `docs/store-submission.md`.
- Play needs an AAB (`./gradlew :app:bundleRelease`), and `versionCode`
  must keep rising across both channels.

## 6. Sources (checked 21 Oct 2026)

- Android developer verification rollout:
  https://android-developers.googleblog.com/2026/03/android-developer-verification-rolling-out-to-all-developers.html
- Rollout markets from 30 Sep 2026:
  https://www.helpnetsecurity.com/2026/06/19/android-developer-verification-rollout-markets/
- Advanced flow for unverified apps:
  https://www.androidauthority.com/how-to-bypass-android-developer-verification-sideloading-rules-3718002/
- Android developer verification: https://developer.android.com/developer-verification
- Play testing requirements (12 testers, 14 days):
  https://support.google.com/googleplay/android-developer/answer/14151465
- Play device verification:
  https://support.google.com/googleplay/android-developer/answer/14316361
- Play registration and identity:
  https://support.google.com/googleplay/android-developer/answer/6112435
- Play target API level:
  https://support.google.com/googleplay/android-developer/answer/11926878
- Play Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
- Play Health apps declaration:
  https://support.google.com/googleplay/android-developer/answer/14738291
- Bluetooth permissions:
  https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Intervals.icu API terms:
  https://forum.intervals.icu/t/intervals-icu-api-terms-and-conditions/114087
