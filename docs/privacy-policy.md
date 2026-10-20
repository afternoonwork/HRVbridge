# HRV Bridge privacy policy (draft)

Draft, not yet published. Placeholders are in [square brackets].

Effective date: [date]

HRV Bridge ("the app") records a short morning heart measurement from your
Bluetooth chest strap and writes the results to your own Intervals.icu
account. This policy explains what the app does with your data. In short:
your data goes from your phone to Intervals.icu and nowhere else.

## What the app measures

While you measure, the app receives from your chest strap your heart rate
and the time between heartbeats (RR intervals). From these it calculates:

- resting heart rate
- rMSSD and SDNN (two heart rate variability values)

The app does not use your location, contacts, photos or any other data on
your phone.

## Where your data goes

- **Only to Intervals.icu, and only with your own API key.** After a
  measurement the app sends your resting heart rate, rMSSD and SDNN for that
  day to your wellness record in Intervals.icu, over an encrypted (HTTPS)
  connection, using the personal API key you entered.
- **No developer server.** The developer of HRV Bridge does not run a server
  for the app and does not receive your measurements, your API key or any
  other data.
- **No analytics, no advertising, no tracking.** The app contains no
  analytics, advertising or tracking code and does not share data with
  third parties.

Once your values are in Intervals.icu, Intervals.icu's own terms and privacy
policy apply to them.

## What is stored on your phone

- **Your measurements**: for each measurement, the raw RR intervals and the
  results are stored in the app's private storage on your phone, so results
  can be checked or recalculated and failed uploads can be retried.
- **Your Intervals.icu API key**: stored encrypted on your phone only
  (Android Keystore, or the iOS Keychain). It is excluded from backups and is
  never written to the debug log.
- **Backups**: your phone's own backup (Google backup or iCloud) may include
  the measurement files. These backups are controlled by you and your phone's
  settings, not by the developer.

## Debug log

The app can create a debug log (Bluetooth events, received data, results)
to help find problems. It is only shared if you tap "Share debug log", and
only with the recipient you choose. It never contains your API key.

## Permissions

- **Bluetooth** (on Android: "Nearby devices"): to find and connect to your
  chest strap.
- **Location (Android 11 and lower only)**: older Android versions require
  the location permission for any Bluetooth scan. The app does not use or
  store your location.
- **Internet**: to send your values to Intervals.icu.

## How to delete your data

- **API key**: tap "Disconnect" in the app. This removes the key from your
  phone. You can also revoke or regenerate the key in Intervals.icu.
- **Measurements on your phone**: uninstalling the app deletes all of them.
  [An in-app "delete all measurements" option is planned.]
- **Values in Intervals.icu**: delete them in Intervals.icu.

## Not a medical device

HRV Bridge is not a medical device and is not intended to diagnose, treat or
prevent any condition. Do not use it for medical decisions.

## Children

The app is not directed at children.

## Changes to this policy

If this policy changes, the new version will be published at [URL] with a
new effective date.

## Contact

[Name], [email address]
