# Play release and alarm reliability checklist

This checklist records the evidence required before publishing AnaAlarm. A checked
repository item means the implementation can be inspected in source. Device runs,
videos, Play Console declarations, signing, and upload results are external release
work and remain **unclaimed** until an owner attaches the requested evidence.

Status convention:

- `[x]` verified from this repository.
- `[ ]` external or not yet verified; do not infer completion from the code.

## Repository and build gates

- [x] `minSdk` is API 26 and `targetSdk` is API 36.
- [x] alarm components are direct-boot aware and listen for
  `LOCKED_BOOT_COMPLETED` and `USER_UNLOCKED`.
- [x] pre-unlock scheduling uses only a minimal device-protected alarm mirror; it
  does not open Room, DataStore, the keystore, speech recognition, or an LLM client.
- [x] fired pre-unlock one-shots are retired before launch, while their snooze
  duration remains available for repeated snoozes.
- [ ] `./gradlew lintRelease testReleaseUnitTest bundleRelease` completes on the
  exact release commit. Attach: CI URL and checksums for the AAB, mapping file, and
  native debug symbols (when produced).
- [ ] release signing, version code/name, privacy policy URL, Data safety answers,
  content rating, countries, and staged-rollout percentage have been reviewed.

## Required physical-device evidence

Run the complete matrix on **one physical API 26 device** and **one physical API 36
device**. Emulator results are useful additional evidence but do not replace these
two physical runs. Use the signed candidate build and clear app data before each
API-level run.

The workflow's `AnaAlarm Internal` APK is useful for day-to-day tester installation, but it has a
different application ID and dedicated internal key. It is not the production-signed candidate,
Play AAB, or final release evidence requested below.

For every row attach: device model, Android build/fingerprint, app version and
version code, timezone, exact-alarm/full-screen/notification permission state,
wall-clock timestamp, result, video URL, relevant `adb logcat`, and
`adb shell dumpsys alarm` excerpt.

| ID | Scenario and exact procedure | API 26 physical | API 36 physical |
| --- | --- | --- | --- |
| AL-01 | Grant required alarm/notification access. Schedule a one-shot two minutes ahead, turn the screen off, leave the device locked, and wait without touching it. Verify sound/vibration starts on time and the wake UI or allowed notification path is visible. | [ ] | [ ] |
| AL-02 | Repeat AL-01 in airplane mode with Wi-Fi off and no API key configured. Verify the local alarm, Stop, and Snooze work without network, credentials, speech, or LLM access. | [ ] | [ ] |
| AL-03 | Schedule a one-shot, reboot, and **do not unlock** after boot. Keep the screen off until the trigger. Verify it rings once from the device-protected schedule and is retired before UI launch. | [ ] | [ ] |
| AL-04 | During AL-03 press Snooze, let it ring, press Snooze a second time, and let it ring again while still locked. Reboot once more before unlocking and verify the original one-shot is not resurrected. Then unlock and verify the credential database records it disabled. | [ ] | [ ] |
| AL-05 | Schedule a repeating alarm for the current weekday, reboot without unlocking, and let it fire. Stop it, inspect `dumpsys alarm`, and verify exactly one next regular occurrence is armed. Unlock and verify reconciliation leaves the alarm enabled with no duplicate pending intent. | [ ] | [ ] |
| AL-06 | Disable an enabled alarm, edit its time/days, and delete another alarm. Reboot before unlock after each operation and verify no obsolete device-protected occurrence rings. | [ ] | [ ] |
| AL-07 | Deny or revoke full-screen-intent access where the OS exposes that control, lock the screen, and fire an alarm. Verify the local foreground alarm continues and the notification provides usable Stop/Snooze actions without a crash. | N/A (no user toggle); record baseline [ ] | [ ] |
| AL-08 | Deny notification permission where supported, fire while locked/screen-off, then restore permission. Record the OS behavior and verify the app explains or recovers from the restricted path without silently changing the alarm schedule. | N/A on API 26; record platform behavior [ ] | [ ] |
| AL-09 | Force-stop is **not** a supported reboot simulation. Separately document force-stop behavior, then reopen the app and verify enabled alarms reconcile without duplicate triggers. | [ ] | [ ] |
| AL-10 | Change timezone and wall-clock time with one-shot and repeating alarms enabled. Verify `TIMEZONE_CHANGED`/`TIME_SET` reconciliation arms the intended local time once. | [ ] | [ ] |

Release evidence record:

| Field | Value |
| --- | --- |
| Candidate commit | _unclaimed_ |
| AAB SHA-256 | _unclaimed_ |
| API 26 evidence folder/URL | _unclaimed_ |
| API 36 evidence folder/URL | _unclaimed_ |
| Tester and review date | _unclaimed_ |
| Known deviations and approver | _unclaimed_ |

## Play Console foreground-service declaration

The uploaded manifest declares the `systemExempted` foreground-service type. In
Play Console open **Policy and programs > App content > Foreground service
permissions** (wording can vary by console rollout). Complete the declaration
shown for the uploaded AAB and preserve a screenshot of every submitted field.

- [ ] Foreground-service type shown by Console: `systemExempted`.
- [ ] Eligible use-case selection: choose the alarm-related use case actually
  offered by Console for this build; record its exact label rather than inventing
  a category.
- [ ] App functionality description (submission draft): "AnaAlarm lets a user
  schedule exact wake-up alarms. At the chosen time it starts a short-lived local
  foreground alarm session that plays sound and vibration and exposes Stop and
  Snooze, including while the device is locked or offline."
- [ ] Why the task cannot be deferred (submission draft): "Deferring a wake-up
  alarm changes the user-selected trigger time and defeats the feature's core
  purpose."
- [ ] Impact if interrupted (submission draft): "The user may not hear or feel the
  alarm and could miss the wake-up event; Stop and Snooze would also be unavailable."
- [ ] Demonstration video URL: _unclaimed_. Use a review-accessible URL (YouTube is
  preferred by Play guidance, or a directly accessible cloud-hosted MP4).
- [ ] Video shot list: clean install; schedule an alarm; lock and turn off screen;
  alarm triggers; foreground notification/full-screen path appears; user presses
  Snooze; alarm triggers again; user presses Stop. Include the app name and the
  submitted build version in-frame or in the description.
- [ ] Reviewer access instructions/test credentials: state that the core local
  alarm flow needs no account or API key, then list the exact permission/setup taps
  needed on the test device.

Google Play requires the foreground-service declaration to describe the
functionality, explain the effect of deferral/interruption, and provide a video of
the user-triggered flow. See [Foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
and [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9214102?hl=en).

## Play Console full-screen-intent declaration

The manifest requests `USE_FULL_SCREEN_INTENT` because showing a user-scheduled
wake alarm is a core alarm function. Complete the declaration under **Policy and
programs > App content > Full-screen intent** for the uploaded AAB.

- [ ] Permission displayed by Console: `android.permission.USE_FULL_SCREEN_INTENT`.
- [ ] Core-function selection: **Setting an alarm** (use the Console's exact current
  label).
- [ ] Justification (submission draft): "The user explicitly schedules a wake-up
  alarm for a specific time. When it fires while the screen is off or locked, the
  full-screen alarm surface provides the time-critical Stop and Snooze controls.
  If the OS does not grant full-screen access, AnaAlarm retains the foreground
  alarm and notification fallback."
- [ ] Demonstration video URL: _unclaimed_. The same video may be referenced only
  if it clearly demonstrates the locked/screen-off full-screen alarm flow and the
  user action that scheduled it.
- [ ] Review instructions: give the exact navigation to create an alarm, the wait
  time, permission state, and note that no sign-in/API key is needed.
- [ ] Save submission confirmation, Console status, reviewer correspondence, and
  the final approval screenshot in the release evidence folder.

Only apps whose core functionality is alarms or calls are eligible for automatic
full-screen-intent access under Play policy; approval is a Console/reviewer result,
not something repository tests can claim. See [Foreground service and full-screen
intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).

## Final external approval

- [ ] Product owner confirms the videos show the exact candidate build.
- [ ] Privacy/security reviewer confirms the device-protected mirror contains only
  alarm ID, time, repeat-day bit mask, snooze duration, and lifecycle state.
- [ ] Release owner confirms all Play Console declarations match observed runtime
  behavior and no field above remains `_unclaimed_`.
- [ ] Staged rollout monitoring owner and rollback criteria are recorded before
  production submission.
