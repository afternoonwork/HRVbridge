# Store submission answers (draft)

Draft for Paul, based on what the code does on 21 Oct 2026 (v1: personal
API key, no OAuth). Items marked **CHECK** are uncertain and need Paul's
decision or a look at the live console forms, whose wording changes.

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
  Session files are included in the phone's own backup (Android Auto
  Backup, iCloud backup); only the key is excluded.
- Deletion: "Delete all measurements" in the app (sessions and debug log),
  "Disconnect" (API key), uninstalling (everything). Data in Intervals.icu
  is deleted there.
- Debug log: shared only when the user taps "Share debug log", to a
  recipient the user picks. Never contains the key.
- Permissions (Android): Bluetooth scan (`neverForLocation`) and connect;
  on Android 11 and lower, fine location, required by Android for BLE
  scans, not used for location; internet. iOS: Bluetooth only.
- Not a medical device.

## Google Play: Data safety

- Does the app collect or share any of the required user data types?
  **Yes, collected** (Health and fitness > Health info: heart rate, HRV).
  **CHECK**: Play counts data sent off the device as collected even when it
  goes to a service the user chose; that is the safe answer.
- Shared with third parties: **No**. Sending to Intervals.icu is a
  user-initiated transfer the user expects, which Play exempts from
  "sharing". **CHECK** wording in the form.
- Processed ephemerally: No (stored by Intervals.icu in the user's account).
- Required or optional: required for the app's purpose (it uploads the
  values); the user can measure without connecting, then nothing is sent.
- Purpose: App functionality.
- Encrypted in transit: **Yes** (HTTPS).
- Users can request deletion: **CHECK**. The developer holds no data, so
  there is nothing to request; local data is deleted in-app or by
  uninstalling, Intervals.icu data in Intervals.icu. Play may still ask
  for a deletion URL: point it at the privacy policy section.
- Other types (location, personal info, app activity, device IDs,
  crash logs): **not collected**. The API key is a credential, not a
  listed data type. **CHECK** none of the newer categories fit it.
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
- Location permission (Android 11 and lower only, `maxSdkVersion 30`):
  foreground only, for Bluetooth scanning. **CHECK** whether Play asks for
  a location declaration for this; no background location is requested.

## App Store: privacy "nutrition label"

Must match `PrivacyInfo.xcprivacy` (PR "iOS: privacy manifest").

- Data used to track you: **None**.
- Data linked to you: **None**.
- Data not linked to you: **Health & Fitness > Health** (heart rate, HRV),
  purpose **App Functionality**.
- **CHECK** (decide once, apply to both label and manifest): Apple counts
  data as "collected" when the developer or its partners can access it.
  Here it goes only to the user's own Intervals.icu account and the
  developer receives nothing, so "Data Not Collected" may also be
  defensible. The current manifest takes the cautious route (Health, not
  linked, app functionality).

## App Store: review notes

- Guideline 1.4.1 (medical): state that the app is not a medical device
  and only forwards the user's own measurements.
- Guideline 5.1.3 (health data): the app does not use HealthKit. Session
  files are in Application Support and therefore in iCloud backup.
  **CHECK**: 5.1.3 forbids storing personal health information in iCloud;
  it is written for HealthKit and research data, but to be safe the
  sessions folder could be excluded from backup (`isExcludedFromBackup`),
  a small code change.
- Bluetooth usage text: "HRV Bridge connects to your heart rate chest strap
  to record your morning measurement."
- Review account: reviewers need an Intervals.icu API key and a strap.
  Provide a test key for a throwaway Intervals.icu account and explain the
  debug-only simulated strap is not in release builds. **CHECK** how to let
  review test without a strap (screen recording, or a review-only mode).
- Age rating: 4+.
