# Alarm System

How alarms are scheduled, fired, and re-scheduled — and what Android versions require.

## 1. Components

```
AlarmEditScreen (save)
     │
     ▼
MemoryStore.upsertAlarm ──► Room alarms table
     │
     ▼
AlarmScheduler.schedule(alarm)
     │  setExactAndAllowWhileIdle(RTC_WAKEUP, nextTrigger, pendingIntent)
     ▼
        AlarmManager
     │
     ▼ (fires)
AlarmReceiver.onReceive
     ├─ posts notification with FULL-SCREEN INTENT ──► WakeUpActivity
     └─ AlarmScheduler.rescheduleNext(id)  (next occurrence for repeating alarms)
```

On boot (or package update): `BootReceiver` → `AlarmScheduler.rescheduleAll()`.

## 2. Scheduling details (`AlarmScheduler.kt`)

- `setExactAndAllowWhileIdle` — fires even in Doze, wakes the device.
- The `PendingIntent` is a broadcast to `AlarmReceiver` with `EXTRA_ALARM_ID`; flags `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`.
- Request code = `alarmId.toInt()` so multiple alarms don't collide.

### Next-trigger computation

```kotlin
now = LocalDateTime.now()
candidate = today at (hour:minute)
if candidate <= now: candidate += 1 day

repeatDays (bitmask, bit0=Sunday … bit6=Saturday)
  if empty  → one-shot: keep candidate (today or tomorrow)
  otherwise → scan candidate..+6 days for the first day whose
              day-of-week bit is set
```

Day bit mapping (single source of truth in `AlarmScheduler.DayOfWeek.bit()`):

| Day | Bit |
|---|---|
| Sunday | 0 |
| Monday | 1 |
| Tuesday | 2 |
| Wednesday | 3 |
| Thursday | 4 |
| Friday | 5 |
| Saturday | 6 |

The UI chips in `AlarmEditScreen` use `bit = (chipIndex + 1) % 7` where chip index 0 = Monday, matching this mapping.

## 3. Firing (`AlarmReceiver.kt`)

1. Builds an intent for `WakeUpActivity` with the alarm id.
2. Posts a notification (`PRIORITY_MAX`, `CATEGORY_ALARM`, bell icon) whose **full-screen intent** opens the wake-up screen — this is the mechanism that starts the activity from the background on modern Android.
3. Re-schedules the next occurrence for repeating alarms (`rescheduleNext`), so a weekly alarm keeps firing.

> Starting activities directly from a background receiver is blocked since Android 10; the full-screen intent is the supported path.

## 4. Wake-up screen behavior

`WakeUpActivity` is configured in the manifest with:

```xml
android:showWhenLocked="true"   <!-- appears above the lock screen -->
android:turnScreenOn="true"     <!-- turns the screen on -->
android:excludeFromRecents="true"
android:launchMode="singleTask"
android:screenOrientation="portrait"
```

plus `FLAG_KEEP_SCREEN_ON` in code so the screen stays awake for the conversation. Theme: dark fullscreen (`Theme.AnaAlarm.WakeUp`).

## 5. Permissions & Android version requirements

| Permission | Required | Notes |
|---|---|---|
| `SCHEDULE_EXACT_ALARM` | Android 12+ | Runtime grant via system settings. The home screen detects denial (`AlarmManager.canScheduleExactAlarms()`) and offers a **Grant permission** button (opens `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`). Without it, `setExactAndAllowWhileIdle` won't fire precisely (silently ignored or inexact). |
| `POST_NOTIFICATIONS` | Android 13+ | Runtime prompt at first launch. Needed for the alarm notification. |
| `USE_FULL_SCREEN_INTENT` | Android 14+ | Allowed by default for alarm/clock apps; some OEMs restrict it in settings. If denied, the alarm still posts a heads-up notification. |
| `RECEIVE_BOOT_COMPLETED` | all | Re-schedule alarms after reboot. |
| `WAKE_LOCK` / `VIBRATE` | all | Screen behavior and vibration support. |
| `RECORD_AUDIO` | all | Speech recognition in the wake-up session. |

## 6. Edge cases handled

| Case | Behavior |
|---|---|
| Reboot | `BootReceiver` re-schedules all enabled alarms |
| App update | `ACTION_MY_PACKAGE_REPLACED` triggers the same re-scheduling |
| Alarm time passed while device off | AlarmManager delivers the missed exact alarm when the device powers on; `BootReceiver` then re-arms the next occurrence |
| Alarm deleted | `AlarmScheduler.cancel` removes the `PendingIntent` |
| Alarm disabled | Toggling off calls `cancel`; enabling re-schedules |
| Permission revoked | `schedule()` is wrapped in `runCatching` — no crash; home screen shows the warning card again |

## 7. Testing notes

- Set an alarm 1–2 minutes ahead for fast iteration.
- Verify the permission-warning card appears when exact alarms are denied, and that **Grant permission** opens the right system screen.
- Test reboot persistence: set a repeating alarm, reboot the phone, confirm it still fires.
- Test with the screen locked — the wake-up screen must appear over the lock screen.
