# Alarm system

How alarms are registered, delivered, made audible, snoozed, and reconciled with Android.

## 1. Delivery flow

```text
AlarmEditScreen.save
  +-- MemoryStore.upsertAlarm -> Room
  `-- AlarmScheduler.schedule -> device-protected mirror
                              -> AlarmManager.setAlarmClock
                                           |
                                           v
                                AlarmReceiver (private action)
                                  +-- before unlock: synchronously
                                  |     retire one-shot or re-arm repeat
                                  +-- AlarmService.start
                                  |     local tone/vibration + wake UI
                                  `-- after unlock: goAsync
                                        disable one-shot or re-arm repeat
```

Scheduling success is explicit. The editor shows a success message only for
`AlarmScheduleResult.Scheduled`; missing exact-alarm permission and framework failures leave the
screen open with an error.

Each enabled Room row is also reduced to a device-protected snapshot containing only alarm id,
wall-clock time, repeat mask, snooze duration, and re-arm state. This mirror contains no settings,
API key, prompt, transcript, or other conversation data.

## 2. Exact scheduling

`AlarmScheduler` uses `AlarmManager.setAlarmClock()`. This is the alarm-clock API, so it wakes in
Doze, receives the strongest idle exemption, and appears in Android's next-alarm affordance.

The delivery PendingIntent:

- explicitly targets `AlarmReceiver`;
- uses the private action `com.anaalarm.action.SCHEDULED_ALARM`;
- carries `EXTRA_ALARM_ID`;
- uses immutable/update-current flags;
- derives request codes from the 64-bit alarm id plus a request-kind namespace.

The main manifest keeps `AlarmReceiver` unexported and declares no delivery intent filter. A
separate debug manifest exports only `com.anaalarm.action.DEBUG_FIRE_ALARM`; receiver code also
checks `BuildConfig.DEBUG`, so the manual ADB path cannot enter a release build.

### Trigger calculation

```kotlin
candidate = today at hour:minute
if (candidate <= now) candidate += 1 day

if (days == 0) candidate                       // one-shot
else first candidate in the next seven days  // selected weekday bit
```

`days` uses bit 0 for Sunday through bit 6 for Saturday. A one-shot is disabled after its first
regular delivery. A repeating alarm is scheduled again for the next selected weekday.

## 3. Receiver lifetime and reconciliation

For an unlocked user, `AlarmReceiver` starts the service immediately, then calls `goAsync()` for
regular deliveries. The pending result is finished only after Room and `AlarmManager` work
completes. Before first unlock, the receiver first commits the small device-protected
one-shot/repeat transition synchronously and then starts the service; it never opens Room on that
path. Both orders prevent a process loss or immediate reboot from resurrecting delivered state.

`BootReceiver` uses the same pattern to reconcile alarms after:

- `LOCKED_BOOT_COMPLETED`;
- `BOOT_COMPLETED`;
- `USER_UNLOCKED`;
- `MY_PACKAGE_REPLACED`;
- `TIME_SET`;
- `TIMEZONE_CHANGED`;
- `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`.

Before first unlock, `AnaAlarmApp` deliberately does not initialize Room, DataStore, Keystore,
DeepSeek, TTS, or speech recognition. Locked-boot and clock-change reconciliation reads only
enabled device-protected snapshots. A regular delivery retires a one-shot in that mirror before
opening UI, or re-arms a repeat directly from its snapshot.

A fired pre-unlock one-shot becomes a tombstoned snapshot: it is excluded from every later boot
re-arm but temporarily retains its snooze duration. On `USER_UNLOCKED`, reconciliation applies the
retirement to Room, removes the tombstone, replaces enabled mirror state from Room, and re-arms the
resulting schedule. Room is authoritative again without resurrecting the fired alarm.

## 4. Audible fallback and full-screen UI

`AlarmService` is a `systemExempted` foreground service: Android defines this type for apps that
hold exact-alarm access and continue alarms in the background, including haptics. As soon as it
starts it:

1. posts a silent ongoing foreground notification with a Stop action;
2. briefly acquires a screen wake lock, always posts the high-priority full-screen notification,
   and sends the wake activity PendingIntent with sender- and Android 15+ creator-side background
   launch opt-ins;
3. starts an alarm vibration waveform and generated alarm-stream tone;
4. prepares the device's default alarm sound asynchronously, replacing the generated tone only
   after the ringtone actually starts.

This local path is intentionally independent of DeepSeek, the API key, network, ASR, and TTS.
`SessionController` stops it only after the matching TTS utterance reports `onStart`, or after an
explicit Stop/successful Snooze. An API error is not a stop action, and merely queueing speech is
not enough.

`WakeUpActivity` is private, excluded from recents, shows over the lock screen, turns the screen
on, and keeps it awake. `WakeUpSessionViewModel` preserves its controller across configuration
changes; `onNewIntent` replaces it when a different overlapping alarm id arrives.

Before first unlock, the same direct-boot-aware activity renders a minimal clock with Stop and,
when a snapshot exists, Snooze. It never instantiates the conversation or voice stack. Once
credential storage becomes available, it transitions to the normal session surface.

## 5. Snooze

The wake-up screen shows Snooze only when it was launched with a real alarm id. The action:

1. reads the alarm's persisted `snoozeMinutes`;
2. registers a distinct exact PendingIntent for `now + snoozeMinutes`;
3. closes the current session only after `Scheduled` is returned;
4. keeps the session open with an actionable error on failure.

Snooze delivery carries `EXTRA_IS_SNOOZE`, so the receiver starts the audible/UI path but skips
regular one-shot/repeat mutation. Explicit disable/delete cancels regular, snooze, and show
intents. One-shot post-fire cleanup cancels only regular/show intents, preserving a snooze that
the user may create while receiver persistence is still finishing.

Before first unlock, Snooze reads only mirrored `snoozeMinutes`. A tombstoned one-shot remains
available for another Snooze when the snooze itself fires, while remaining ineligible for regular
re-arming after another reboot.

## 6. Permissions

| Permission | Android | Behavior when missing |
|---|---|---|
| `SCHEDULE_EXACT_ALARM` | 12+ | `schedule()` returns `EXACT_ALARM_PERMISSION_REQUIRED`; save cannot report success |
| `POST_NOTIFICATIONS` | 13+ | Full-screen/foreground notification visibility is impaired; request at first launch |
| `USE_FULL_SCREEN_INTENT` | 14+ | Local sound/vibration continues; user may need to tap the notification |
| `RECEIVE_BOOT_COMPLETED` | all | Schedules cannot be reconciled after reboot |
| `WAKE_LOCK` / `VIBRATE` | all | Screen wake or vibration fallback is reduced |
| `RECORD_AUDIO` | all | Conversation switches to typed input; alarm audio still works |

## 7. Required manual checks

- Save with exact-alarm permission denied: no success message and no registered alarm.
- Reboot without unlocking, keep airplane mode enabled, and let a real alarm fire: sound/vibration
  and the minimal Stop/Snooze UI must work without Room, credentials, AI, TTS, or ASR.
- Fire a pre-unlock one-shot, Snooze it twice, then reboot again before unlock: snoozes work while
  the original regular one-shot never re-arms. Unlock and verify Room shows it disabled.
- Verify one-shot disables after delivery, while Snooze still fires once.
- Verify repeat days re-arm correctly across a time-zone change and a reboot followed by unlock.
- Remove/disable an alarm with a snooze pending: neither regular nor snooze delivery may fire.
- Deny full-screen intent: local audio must remain audible and notification tapping must open the
  wake-up screen.

Automated coverage is mapped in [TESTING.md](TESTING.md); latency and callback invariants are in
[RELIABILITY_AND_LATENCY.md](RELIABILITY_AND_LATENCY.md). Required physical API 26/API 36 runs and
Play Console evidence are tracked in [PLAY_RELEASE_CHECKLIST.md](PLAY_RELEASE_CHECKLIST.md).
