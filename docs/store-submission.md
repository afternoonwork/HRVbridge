# Store submission answers (draft)

Draft for Paul, based on what the code does on 21 Oct 2026 (v1: personal
API key, no OAuth). Paul's decisions of 21 Oct 2026 are applied. Items
marked **CHECK** still need a look at the live console forms, whose
wording changes.

## Facts the answers rest on

- Measured: heart rate and RR intervals from a Bluetooth chest strap.
  Computed: resting HR, rMSSD, SDNN.
- Sent off the device: only resting HR, rMSSD, SDNN (plus the date) to
  the user's own Intervals.icu account, over HTTPS, with the user's own
  API key. No developer server; the developer receives nothing.
- No analytics, advertising, crash reporting or other SDKs. Dependencies
  are platform and AndroidX/Compose libraries only.
- Stored on the phone: session files (raw RR, results), the API key
  (Android Keystore / iOS Keychain, excluded from backup), a few settings.
  Session files follow the platform backup defaults (Android Auto Backup,
  iCloud backup); users control backups in their phone settings.
- Deletion: "Delete all measurements" in the app (sessions and debug log),
  "Disconnect" (API key), uninstalling (everything). Data in Intervals.icu
  is deleted there.
- Debug log: shared only when the user taps "Share debug log", to a
  recipient the user picks. Never contains the key.
- Permissions (Android): Bluetooth scan (`neverForLocation`) and connect;
  on Android 11 and lower, fine location, required by Android for BLE
  scans, not used for location; internet. iOS: Bluetooth only.
- Not a medical device.

## Why "no data collected" (decided by Paul, 21 Oct 2026)

The developer runs no server and never receives or can access any user
data. The app sends values only to the user's own Intervals.icu account,
with the user's own key, under Intervals.icu's terms.

- Apple counts data as collected only when the developer or its partners
  can access it. Neither can, so: Data Not Collected.
- Google Play's Data safety help says data an app sends to the user's own
  account with another provider (its example: uploads to the user's own
  Google Drive or Dropbox), where the app developer never accesses the
  data, need not be declared:
  https://support.google.com/googleplay/android-developer/answer/10787469

## Google Play: Data safety

- Does the app collect or share any of the required user data types?
  **No.** See the note above.
- With no data collected, the form should skip the encryption-in-transit
  and deletion questions. **CHECK** in the live form. (The upload uses
  HTTPS anyway.)
- Data deletion: **not applicable**. No data is collected and there are no
  accounts. Local data is deleted in-app or by uninstalling.
- Privacy policy URL: required; publish `docs/privacy-policy.md` first.

## Google Play: Health apps declaration

- Category: fitness and wellness (heart rate and HRV tracking for
  training), **not** medical. No diagnosis, treatment or medical claims.
- Health Connect: **not used** in v1 (no declaration of Health Connect
  data types needed). It is a "later destination" in CLAUDE.md; using it
  later needs the Health Connect permissions declaration.
- Disclaimer "not a medical device" in the store listing and privacy
  policy. **CHECK** the current form's exact options.

## Google Play: content rating (IARC)

- Category: Utility / productivity type app (health and fitness tool),
  not a game.
- Violence, sexuality, language, controlled substances, gambling: none.
- User interaction / user-generated content: none; no chat, no sharing
  between users. Data goes to the user's own account only.
- Shares location: no. Digital purchases: none (free, donation link only
  outside the app).
- Expected rating: Everyone / PEGI 3 / USK 0.

## Google Play: other declarations

- Ads: contains no ads.
- Target audience: adults (18+ recommended, sports users); not directed at
  children.
- Location permission (Android 11 and lower only, `maxSdkVersion 30`),
  if Play asks why: "Only to scan for Bluetooth heart rate straps on
  Android 11 and older; location is never accessed." No background
  location is requested.

## App Store: privacy "nutrition label"

Must match `PrivacyInfo.xcprivacy` (PR "iOS: privacy manifest"), which
declares no collected data types.

- **Data Not Collected.** See the note above.

## App Store: review notes

- Guideline 1.4.1 (medical): state that the app is not a medical device
  and only forwards the user's own measurements.
- Guideline 5.1.3 (health data): the app does not use HealthKit and the
  developer collects no health data.
- Bluetooth usage text: "HRV Bridge connects to your heart rate chest strap
  to record your morning measurement."
- Apps that need hardware: Apple asks for a video (filmed, not a screen
  recording) of the app on a physical device pairing with and using the
  hardware:
  https://developer.apple.com/help/app-review/before-submitting-for-review/complete-review
- Age rating: 4+.

Draft text for the App Review notes field:

> HRV Bridge records a 1-minute morning heart rate measurement from a
> Bluetooth chest strap and writes resting heart rate and HRV (rMSSD,
> SDNN) to the user's own Intervals.icu account. It is not a medical
> device and does not use HealthKit.
>
> Hardware: any chest strap with the standard Bluetooth Heart Rate
> Service that sends beat-to-beat (RR) data, for example Polar H10 or H9.
> Optical arm or wrist sensors usually do not send RR data; the app then
> says so and does not measure.
>
> How to use it:
> 1. On the connect screen, paste the Intervals.icu API key below (or
>    skip; the measurement still works, the upload then waits).
> 2. Put on the strap with moistened electrodes, tap Start. The app finds
>    the strap, checks the signal, waits for the settling time, then
>    measures for 60 seconds with the screen kept on.
> 3. The result (resting HR, rMSSD, SDNN) is shown and uploaded to today's
>    wellness record in Intervals.icu.
>
> Video of the app pairing with a Polar H9 and completing a measurement on
> an iPhone: [link or attachment].
>
> Test Intervals.icu account (created for review, holds no real data):
> API key [test key].

**CHECK** the steps against the release UI once the redesign is done.

## Paul's release checklist

- Replace the placeholder app name, package ID (Android) and bundle ID and
  team (iOS).
- Create the Android upload key (see CLAUDE.md, Android specifics).
- Publish the privacy policy and use its URL in both stores.
- Create a throwaway Intervals.icu account and API key for App Review.
- Film the review video: the app on a physical iPhone pairing with the
  strap and completing a measurement (a real camera recording, not a
  screen recording).
