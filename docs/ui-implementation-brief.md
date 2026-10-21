# SignON UI implementation brief

Oct 21, 2026 · Paul Müller (copied from the claude.ai project "HRV app")

## Goal and scope

Replace the plain form UI on iOS, then Android, with the designed UI: one main screen built around a heart rate dial, a connect screen and a settings screen. Behaviour stays as CLAUDE.md specifies, apart from the small changes in Order of work.

- **Sources of truth:** CLAUDE.md for behaviour; the [SignON design system](https://claude.ai/artifact/5wmnS8BGBsg7cZVhkCjG4L) for tokens, components, motion and screen states; the [design canvas](https://claude.ai/artifact/NxhmJseNha3c6vjGEQEbwm), page "App flow", for every screen. If you cannot open the links, this brief is self-contained.
- **Precedence:** design system over this brief; Apple Human Interface Guidelines and Material Design 3 over both. Never bend a platform convention.
- **iOS first:** `ios/HRVBridge` (SwiftUI, iOS 17 target, Xcode 26.3, iPhone SE 2020 on iOS 26.6.1 is the test device). Then Android: `app/` (Compose, BOM 2026.06.01).
- **No new libraries.** Draw the dial with SwiftUI `Canvas` and Compose `Canvas`/`DrawScope`; animate with built-in APIs.
- **Out of scope:** `:core` and `HRVCore` maths, new features, charts or history. Debug-only tools (simulated strap, raw recording) keep plain lists in debug builds.
- **Branches:** `ios/ui-redesign` and `android/ui-redesign`, one PR each, per the repo rules in CLAUDE.md.

## Tokens

Define these once per platform (an asset catalogue colour set or a `Theme` enum on iOS, a `ColorScheme` plus a small extension on Android) and use only the names in UI code. Light and dark follow the system.

| Token | Light | Dark | Use |
| --- | --- | --- | --- |
| bg | #FDFDFC | #131312 | Screen background |
| surface | #FFFFFF | #1C1C1A | Dial face, cards, lists, secondary keys, fields, dialogs |
| key | #F1F1EF | #2B2A28 | Disabled key, empty tracks |
| ink | #1A1A1A | #ECEAE4 | Primary text, ticks, hand at rest and settling, primary key fill |
| ink-2 | #5F5F5C | #A2A09A | Secondary text, numerals, done steps; settling band at 45% |
| line | #E2E2DF | #36352F | Hairlines only (decorative) |
| line-strong | #8A8A86 | #74726C | Text field border (3:1) |
| accent | #E2561C | #FF6A2B | Recording and uploaded only. Never text. Graphics rule 3:1 applies |
| destructive | system red | system red | iOS `role: .destructive`, Android `colorScheme.error`. Never the hex in code |

**Type** (system fonts: SF Pro, Roboto; all scale with Dynamic Type and font scaling except the dial numerals):

| Style | Size / line | Weight | iOS | Android | Used for |
| --- | --- | --- | --- | --- | --- |
| readout-hr | 32 / 38 | 600, tabular | size 32 relativeTo .title | 32.sp | HR in the dial window (no unit) |
| readout-timer | 34 / 41 | 500, tabular | .largeTitle | 34.sp | Countdown |
| readout-value | 28 / 34 | 500, tabular | .title | 28.sp | rMSSD, SDNN |
| title | 28 / 34 | 700 | .title bold | headlineMedium | Connect screen title |
| headline | 17 / 22 | 600 | .headline | titleMedium | Nav title, key labels, notice leads |
| body | 17 / 22 | 400 | .body | bodyLarge | List rows, fields |
| callout | 15 / 21 | 400 | .subheadline | bodyMedium | Notices, phase label |
| footnote | 13 / 18 | 400 | .footnote | bodySmall | Strap row, footers, secondary lines |
| caption | 12 / 16 | 400 | .caption | labelMedium | Step labels, field and metric labels |
| dial-numeral | 11 / 13 | 400, tabular | fixed | fixed | Dial numerals |

**Spacing** (pt or dp): 4, 8, 12, 16, 24, 32, 36. Screen side margin 24 (settings lists 16). Dial to phase readout 36; dial to result metrics 32; step indicator to dial 12; bottom margin under the key 28.

**Radius:** dial window 10, fields and list groups 12, notices 14, keys fully rounded (pill).

**Shadows:** key = top highlight (white 90% light, 6% dark) + 0 3 8 black 12% (dark 55%); dial = same highlight + 0 6 18; window and fields = inset 0 1 3. Keep them subtle.

**Sizes:** dial 264 on a 375 wide screen, min(264, width minus 112), at most 300; primary key height 56 (min width 200); secondary key 52 (min width 180); touch targets 44 pt / 48 dp; indicator dots 8.

**Android ColorScheme mapping:** primary = ink, onPrimary = bg, background = bg, surface = surface, onSurface = ink, onSurfaceVariant = ink-2, outlineVariant = line, outline = line-strong, tertiary = accent, error = platform default. No dynamic colour.

## Screens and states

Three screens. Everything from finding the strap to the uploaded result happens on the main screen; the dial stays in place and only the rows above and the text and key below it change.

1. **Connect** (shown first while no API key is saved): title "Connect to Intervals.icu", the sentence "Paste your personal API key. You find it in Intervals.icu under Settings, Developer Settings.", labelled field "API key", "Stored on this phone only and sent only to Intervals.icu.", the notice "Keep your morning values intact" with the full CLAUDE.md text, key "Save key" (disabled while empty). States: key rejected (field border 2 pt ink, warning line "Intervals.icu did not accept this key. Check that you copied all of it, then save again."), connected (card "Connected", key "Continue").
2. **Main** (nav title "SignON", sliders icon right, label "Settings" for accessibility, hidden while measuring).
3. **Settings** (pushed): Measurement: Settling time (0, 30, 60, 90 s; footer "Time to sit still before the 60 s measurement. Beats from this time are not used."). Intervals.icu: API key Connected, Disconnect (destructive). Data: Delete all measurements (destructive, confirmation with the existing text), Debug log (pushed, Share). Footer "Measurements are stored on this phone only."

**Main screen states**

| State | Above the dial | Dial | Below the dial | Key |
| --- | --- | --- | --- | --- |
| No strap | hollow dot, "No strap connected" | faint, "no strap", no hand | notice "Put the strap on and moisten the electrodes. Close other apps that connect to your heart rate monitor." | Find strap |
| Searching | spinner, "Looking for heart rate straps…" | faint, "searching" | list of found straps (name, dBm), tap to connect | none |
| Connecting | spinner, "Connecting to <name>…" | faint, "connecting" |  | none |
| Connected | ink dot, name (bold), "connected" | live HR, ink hand at 12 | "Sit still, then start." and "<n> s settling, then a 60 s measurement." | Start (orange dot) |
| Couldn't connect (15 s) | hollow dot | faint, "no strap" | warning "Couldn't connect to <name>. Check the strap is on and the electrodes are moist. Close other apps that connect to your heart rate monitor." | Try again |
| Bluetooth off | hollow dot | faint, "Bluetooth off" | warning "Bluetooth is off. Turn it on in Control Centre or Settings, then try again." | Try again |
| Not allowed | hollow dot | faint, "no access" | warning "Bluetooth access is not allowed. SignON needs it to find your strap. Allow it in Settings." | Open Settings |
| Poor contact | steps | window "poor contact" | phase and countdown inline, warning "Poor contact. Moisten the electrodes and make sure the strap fits snugly." | Stop |
| Settling (its first 10 s also run the signal check) | steps: settle | ink hand, grey band | "Settling", countdown, "Sit still and breathe normally." | Stop |
| Measuring | steps: measure (orange) | orange hand and band | orange dot "Recording", countdown, "<n> beats recorded" | Stop |
| Failure (no RR, no data, implausible, disconnected) | steps: current step hollow | live HR or faint | warning with the existing CLAUDE.md / FailReason sentence | Done (no RR) or Try again |
| Done (nothing uploads automatically) | steps: upload hollow | full orange ring, resting HR, hand at 12 | rMSSD, SDNN, "<accepted> of <total> beats used" | Upload to Intervals.icu; text key "close without uploading" (lowercase, deliberately); after the upload there is one "Done" key |
| Done, uploading (after "Upload to Intervals.icu", or the questions page if on) | steps: upload hollow | full orange ring, resting HR, hand at 12 | rMSSD, SDNN, "<accepted> of <total> beats used", spinner "Uploading to Intervals.icu…" | Done |
| Uploaded | steps: upload orange | full orange ring | orange dot "Uploaded to Intervals.icu" + tick | Done |
| Unreliable | steps: upload hollow | full grey ring | values in ink-2, warning "Measurement unreliable, please repeat. <n> of <total> beats were discarded." | Repeat; text key "Upload anyway" |
| Not enough beats | steps: upload hollow | full grey ring, no values | warning "Not enough beats to calculate results. Please repeat." | Repeat |
| Upload failed | steps: upload hollow | full orange ring | warning "Upload failed. Your result is saved on this phone. The app will try again next time it opens." | Done |
| Not connected to Intervals.icu / key rejected on upload | steps: upload hollow | full orange ring | the existing CLAUDE.md sentences as warnings | Done |
| Cancelled (left the screen) | warning at the top: "The measurement was cancelled because the app left the screen. Keep it open while measuring." | live |  | Start |
| Earlier uploads retried on open | the existing retry sentences as a footnote line under the strap row |  |  |  |

**Dialogs:** Stop asks "Stop the measurement?" / "Nothing is saved or uploaded." with "Stop" (destructive) and "Keep going" (cancel). iOS `.alert`, Android M3 `AlertDialog`; on Android, back and predictive back open the same dialog during a measurement. Delete all: iOS `.confirmationDialog`, Android `AlertDialog`.

**Android differences:** OutlinedTextField with its own error state; scan auto-connects (remembered strap, else strongest after 3 s), so show the list only when two or more straps are found; ask Nearby devices (12+) or Location (11 and lower) before the first scan; Bluetooth off key reads "Turn on Bluetooth" and starts the system request; settling time as a single-choice dialog; top app bar with back arrow in Settings.

## The dial

Build the dial as one reusable view (`HeartDial` in SwiftUI, `HeartDial` composable) drawn entirely in code. It takes: latest heart rate (or a state label), hand mode (hidden, ink, accent), hand angle, band mode (none, settle, measure, muted), band angle, and faint.

| Part | Geometry (R = dial radius, 132 at 264) | Colour |
| --- | --- | --- |
| Face | circle, 1 pt outline, dial shadow | surface, line |
| Band | ring 0.932 R to 0.970 R (5 pt), from 12 o'clock clockwise, butt ends | settle and muted: ink-2 at 45%; measure: accent |
| Minor ticks (55) | 1 pt, 0.856 R to 0.917 R | ink |
| Major ticks (every 5th) | 2.5 pt, 0.818 R to 0.917 R | ink |
| Numerals 5 to 60 | centred on 0.712 R, dial-numeral | ink-2 |
| Window | 96 x 46, radius 10, centre 0.379 R below the dial centre, inset shadow | bg, line |
| Window content | HR in readout-hr, no unit; or a label ("no strap", "searching", "connecting", "Bluetooth off", "no access", "poor contact") in footnote 600 | ink; label ink-2 |
| Hand | 2 pt, round caps, from 0.136 R behind the centre to 0.879 R | ink or accent |
| Hub | radius 7, centre dot radius 2 | same as hand; dot surface |
| Faint | ticks and numerals at 35% opacity |  |

**Hand and band rules** (replaced 21 Oct 2026 after Paul's iPhone test)

- The hand sweeps continuously like a mechanical watch's seconds hand, driven by the wall clock: one revolution over the settling period, one over the 60 s measurement, parked at 12 when done. The angle is cumulative: settling 0 to 360, measuring 360 to 720, done 720. With settling 0 s there is no first turn.
- The band runs from 12 to the hand within the current turn (it follows the hand). Done, reliable: full accent ring. Unreliable or not enough beats: full grey ring.
- Heartbeats are shown by the orange ring "breathing" in time with the heart, not by the hand: on each accepted beat the ring's opacity eases up and fades back down over that beat's RR interval (smooth, never on/off; 35% to 100%). Only while measuring; settling and done states are static.
- Hand and hub switch from ink to accent as the hand crosses 12 into measuring.
- The window updates on every strap packet (about 1 Hz), without animation.
- The window is filled with orange #E2561C (the light theme accent) in both themes, with white text and no border; "poor contact" is 14 bold so it counts as large text (white on #E2561C is about 3.6:1).

The measurement controller exposes the running phase's start and length on its clock (for the hand) and a pulse per accepted beat with its RR (for the ring). The per-beat hand and the latest-beat timestamp are gone.

## Motion and haptics

- **Hand:** a continuous sweep with the wall clock, redrawn every frame (SwiftUI `TimelineView(.animation)`, Compose `withFrameMillis`).
- **Breathing ring:** per accepted beat, opacity from 35% up to 100% over about a quarter of the RR interval, then a smooth fade back over the rest (iOS `keyframeAnimator`, Android driven by `withFrameMillis`).
- **Colour change** into measuring: 200 ms cross-fade of hand and hub.
- **Keys:** press scales to 0.98 and drops the outer shadow in 90 ms. Use a custom `ButtonStyle` on iOS and `Button` / `OutlinedButton` / `TextButton` with a pill shape on Android, so pressed states and accessibility come from the system.
- **Reduce Motion** (iOS) / **Remove animations** (Android): the hand still sweeps every frame (it carries information) and the ring still breathes 35% to 100% (opacity only, no movement); colours switch instantly. Spinners stay system spinners.
- **Screen:** keep it on while measuring (`isIdleTimerDisabled`, `FLAG_KEEP_SCREEN_ON`); foreground only, as today.

| Moment | iOS `.sensoryFeedback` | Android `performHapticFeedback` |
| --- | --- | --- |
| Strap connected | `.success` | `CONFIRM` |
| Recording starts (measurement begins) | `.impact(weight: .medium)` | `CONFIRM` |
| Poor contact appears | `.warning` | `REJECT` |
| Finished, reliable | `.success` | `CONFIRM` |
| Finished, unreliable | `.warning` | `REJECT` |
| Uploaded | `.success` | `CONFIRM` |
| Upload failed, strap failure, key rejected | `.error` | `REJECT` |

`CONFIRM` and `REJECT` need API 30; on API 26 to 29 use `VIRTUAL_KEY` and `LONG_PRESS`. No haptic per heartbeat.

## Accessibility and copy

- **Dial:** one accessibility element with a combined label, for example "Heart rate 53 beats per minute. Recording, 36 seconds left." Update the value at most every 5 s; announce phase changes immediately.
- **Step indicator:** one element, "Step 2 of 3: measure". Steps differ by fill and weight, not only colour.
- **Warnings** are announced when they appear (iOS `AccessibilityNotification.Announcement`, Android `liveRegion = Polite`). They are never orange or red: outline, icon and words carry them.
- **Text size:** everything except the dial numerals follows Dynamic Type and font scaling. At the largest sizes the readout may wrap; the dial stays fixed. Nothing may be cut off on the iPhone SE at default size.
- **Contrast:** all text pairs in the token table pass WCAG AA in both themes. Use line-strong for the field border. Accent is graphics only.
- **Copy:** British English, sentence case. Never an em dash and never "--" in any string. Reuse the existing strings from CLAUDE.md and the current views unless this brief gives new ones. Results: resting HR as an integer, rMSSD and SDNN with one decimal, units "ms" and "s".

## Order of work

1. Tokens and type as platform code (both themes), plus the Key, Notice and StatusLine styles.
2. `HeartDial` with a preview driven by the simulated strap (debug), checked at 1x on the iPhone SE in light and dark, with Reduce Motion on and off.
3. Main screen state machine wired to the existing `HeartRateCentral` and `MeasurementController`, then Connect and Settings.
4. Haptics, accessibility labels and announcements.
5. Android port of the same, then the Android differences.

**Behaviour changes this redesign needs (agreed with Paul):**

1. The settling time picker moves from the main screen to Settings; the main screen states the value in words.
2. Stop asks for confirmation instead of cancelling at once.
3. After "Save key", make one authenticated read call to Intervals.icu to check the key and show "key rejected" on 401/403. Test it against a local server only: no live Intervals.icu calls from cloud sessions.
4. ~~Per-beat hand~~ Replaced on 21 Oct 2026: the hand sweeps with the wall clock and beats show as the breathing ring (see Hand and band rules). No change to filtering or maths.
5. An unreliable or too-short result offers "Repeat", which starts a new measurement with the same strap.

**Changes after Paul's iPhone test (21 Oct 2026, these replace the text above where they differ):**

1. Copy: never name other apps. "Close other apps that connect to your heart rate monitor." in the no-strap notice and the couldn't-connect warning.
2. Copy: "sit still", never "lie still".
3. No separate signal check phase. The RR-presence and plausibility checks run on the first 10 s of settling (or of the measurement when settling is 0 s) and abort with the same failure messages. The step indicator has three steps: settle, measure, upload; the strap row shows the connection. Poor contact before the check has passed holds the phase's clock, as before.
4. Dial motion: a wall-clock seconds hand plus a breathing orange ring for heartbeats (see Hand and band rules and Motion).

**Key check endpoint (verified 21 Oct 2026 in the live OpenAPI spec):** `GET https://intervals.icu/api/v1/athlete/0/wellness/{today}` ("Get wellness record for date", local ISO-8601 day). It needs only the same wellness access as the upload and returns a small record: 2xx = key valid, 401/403 = key rejected, anything else = "couldn't check, saved anyway". (`GET /api/v1/athlete/{id}` also exists but returns the full athlete with sport settings.)

**Open points**

- The Polar H9 does not report the contact bit, so "poor contact" will rarely show on Paul's strap: test it with the simulated strap.
- The app name is still a placeholder; keep the title string in one place.
- The canvas still draws the API key field with a hairline border; the design system's line-strong border is correct.

**Done when**

- [ ] Every state in the Screens and states table is reachable and matches the canvas on the iPhone SE (375 x 667) without scrolling during a measurement
- [ ] Light and dark both checked; no pure white or black backgrounds
- [ ] Hand sweeps with the wall clock, never moves backwards, crosses 12 into recording; the orange ring breathes once per accepted beat while measuring
- [ ] Reduce Motion / Remove animations switches colours instantly; the hand still updates every frame and the ring still breathes
- [ ] Nothing uploads automatically: the result shows "Upload to Intervals.icu" and the text key "close without uploading", then one "Done" key once the upload has happened ("Repeat" and "Upload anyway" when unreliable)
- [ ] Dial window orange #E2561C with white text in light and dark; "poor contact" 14 bold fits without ellipsis
- [ ] Haptics fire only at the listed moments
- [ ] VoiceOver and TalkBack read the dial and steps as specified
- [ ] Destructive actions use the platform's destructive role
- [ ] No em dashes or "--" in any string; British English
- [ ] `:core` and `HRVCore` tests still pass unchanged
