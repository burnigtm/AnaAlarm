# Physical release-gate validation

Closes out the three unchecked boxes in
[issue #6](https://github.com/burnigtm/AnaAlarm/issues/6): the gates that, by that issue's own
rule, "are not inferred from CI" — real speaker audibility, screen-off/keyguard behavior on
physical hardware, and Play Console declarations.

Everything here can be executed by one operator with two physical devices and a workstation.
The companion tooling (`scripts/device-validation.ps1` / `.sh`) automates evidence capture per
device; this document is the procedure it supports.

---

## 0. Prerequisites

| Requirement | Why |
|---|---|
| Two physical devices: **Device 1 = API 26**, **Device 2 = API 36** (any OEM) | Scenario B's matrix; Scenario A may use either device |
| USB debugging enabled, serial visible in `adb devices` | Evidence tooling targets each serial |
| A signed installable build on each device: the CI `AnaAlarm-internal-*.apk` (preferred — matches distribution reality) or a debug build | Debug builds additionally allow the `ACTION_DEBUG_FIRE_ALARM` broadcast shortcut; internal builds schedule through the normal UI path documented below |
| Screen lock set to **PIN** (not Swipe/None) | Keyguard must be real for Scenario B |
| Device unplugged from USB after setup steps that require it | USB keeps some devices awake and masks screen-off behavior; use wireless ADB (`adb tcpip 5555` + `adb connect <ip>:5555`) so evidence capture survives unplug |
| Volume up, media/alarm stream audible | The point of Scenario A |

Tooling:

```powershell
# Windows
powershell -File scripts\device-validation.ps1 -Scenario A          # auto-detects serials
powershell -File scripts\device-validation.ps1 -Scenario B -Serials <serial1>,<serial2>
```

```bash
# macOS / Linux / WSL
bash scripts/device-validation.sh --scenario A            # auto-detects serials
bash scripts/device-validation.sh --scenario B <serial1> <serial2>
```

Each run writes `artifacts/device-validation-<serial>/` containing `device-info.txt`, the
captured logcat window, an optional screen recording, and a pre-filled
`results-checklist.md`. Attach those bundles to issue #6 when ticking a box.

---

## 1. Scenario A — audible fallback with airplane mode and no API key *(issue box 5)*

**Claim under test:** with no network and no API credential, local alarm sound and vibration
start immediately at delivery and continue until an explicit Stop or Snooze.

### Setup (per device)

1. Install the build and launch AnaAlarm once; grant notification permission. Do **not**
   enter an API key (`pm clear com.anaalarm.internal` guarantees a clean no-key state).
2. Enable airplane mode: Settings → Network, or `adb -s <serial> shell cmd connectivity
   airplane-mode enable` (works on most builds; otherwise toggle manually).
3. Verify no key survived: app → Settings → the key field shows the saved-and-hidden hint only
   if you intentionally kept one; for this test remove it via *Remove key*.

### Run

4. Note the current time, then create a repeating-free (one-shot) alarm **2–3 minutes ahead**
   through the app UI. Press Save and confirm the scheduled-for snackbar.
5. Turn the screen off and let the device lock. Keep it untouched until delivery.
6. On delivery, start the evidence window (or run the validation script's Scenario A mode,
   which performs steps 7–9 for you):

   ```bash
   adb -s <serial> logcat -c
   # ... wait for delivery; do not touch the device ...
   adb -s <serial> logcat -d -v threadtime > artifacts/device-validation-<serial>/scenario-A-logcat.txt
   ```

7. **Observe without touching:** sound + vibration must be clearly audible/feelable for at
   least 60 seconds. Record the observation in the checklist.
8. Wake the device, open the wake-up screen, and press **Snooze** (or Stop). Sound/vibration
   must cease within a second or two of the tap.
9. Optionally snooze-verify: the rescheduled exact alarm appears as the system's next alarm
   (`adb -s <serial> shell dumpsys alarm | grep -i anaalarm`).

### Pass criteria

- [ ] Audible ringtone or tone **and** vibration persisted ≥60 s before any interaction.
- [ ] Neither ceased because of the airplane-mode/no-key condition itself.
- [ ] Explicit Snooze or Stop silenced both within ~2 s.
- [ ] `scenario-A-logcat.txt` contains `event=latency metric=alarm_to_first_audio …
      outcome=started` (the delivery→first-audible-audio measurement) and the
      `AlarmReceiver fired alarmId=` line.

---

## 2. Scenario B — screen-off/keyguard delivery on API 26 and API 36 hardware *(issue box 6)*

**Claim under test:** the full-screen wake-up experience delivers over a locked, screen-off
device on old and new Android alike, degrading exactly as documented when an OEM restricts
full-screen intents.

### Matrix

| | Device 1 | Device 2 |
|---|---|---|
| Android | **API 26** (8.0) hardware | **API 36** hardware |
| Lock | PIN set | PIN set |
| Build | same installable APK | same installable APK |
| Evidence dir | `device-validation-<serial26>/` | `device-validation-<serial36>/` |

Repeat the identical procedure on both devices; record both outcomes separately.

### Run (per device)

1. Confirm the PIN lock works: press power, wake, verify the keyguard demands the PIN.
2. Schedule a one-shot alarm 2 minutes ahead via the UI (same as Scenario A step 4).
3. Press power to sleep. Leave locked.
4. At delivery: the device must turn its screen on and show the **wake-up conversation
   screen over the keyguard**. Capture proof:

   ```bash
   adb -s <serial> exec-out screencap -p > artifacts/device-validation-<serial>/scenario-B-wake-screen.png
   adb -s <serial> logcat -d -v threadtime > artifacts/device-validation-<serial>/scenario-B-logcat.txt
   ```

   (Optional but valuable: `adb -s <serial> shell screenrecord --time-limit 120
   /sdcard/b.mp4` started before delivery; pull afterwards.)
5. Record whether entering the PIN was required to interact (expected: the wake screen shows
   over the keyguard; dismissing fully still asks for the PIN on secure locks).
6. Answer one spoken turn or type one answer, then Stop. Confirm the session closes and the
   alarm notification clears.

### OEM fallback duty

If either device suppresses the full-screen launch (notification-only arrival), do **not**
fail the run silently: record make/model/UI version in the checklist, capture the
heads-up-notification screenshot, and note which fallback path delivered the user
(ringing notification → tap). This is precisely the documented OEM behavior the fallback
exists for and feeds the Play Console demonstration video.

### Pass criteria (per device)

- [ ] Screen turned itself on at delivery; wake-up screen visible over keyguard.
- [ ] Local alarm audio was already playing when the screen came on (Scenario A evidence on
      the same build covers audibility; here confirm visually).
- [ ] Stop closed the session and cleared the alarm notification.
- [ ] `scenario-B-logcat.txt` contains `ActivityManager: Displayed
      com.anaalarm/.ui.wakeup.WakeUpActivity` and the `AlarmService` foreground start.
- [ ] If FSI was restricted by the OEM: heads-up-notification screenshot attached + make/model
      recorded (counts as pass-with-documented-fallback).

---

## 3. Scenario C — Play Console declarations and demo video *(issue box 7)*

Not device-executable; completed in the Play Console once Scenarios A/B footage exists.

- [ ] **Foreground service declaration**: type `systemExempted`; justification text = the
      service continues a user-scheduled exact alarm with local sound/haptics and presents the
      wake-up UI (see `docs/ALARM_SYSTEM.md` and manifest
      `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`).
- [ ] **Full-screen intent declaration**: alarm clock use case; reference the
      `USE_FULL_SCREEN_INTENT` permission and the Scenario-B recording.
- [ ] **Demonstration video**: screen-recorded footage of Scenario B delivery over keyguard on
      both devices plus Scenario A's ringing-until-stop; keep ≤30 s, show the alarm firing
      without user input.
- [ ] Data-safety form: conversation text leaves the device to DeepSeek (user-supplied key);
      see README §14.

---

## 4. Closing issue #6

For each completed box:

1. Tick the checkbox in the issue body.
2. Attach the matching `device-validation-<serial>/` bundle(s) (zip them) or Play Console
   screenshots.
3. Reference this document and the run numbers/devices used in a comment, so the next release
   gate has a baseline to re-run against.
