# Testing

AnaAlarm ships two automated suites plus a manual checklist for the things only a human with a
real phone can confirm.

| Suite | Location | Size | Runtime | Needs a device? |
|---|---|---|---|---|
| JVM unit tests | `app/src/test` | 46 tests / 12 classes | ~15 s | No |
| Instrumented tests | `app/src/androidTest` | 145 tests / 19 classes | ~3 min | Yes |
| Manual checklist | this document, §5 | 60+ checks | ~30 min | Yes, ideally overnight |

Neither automated suite needs a DeepSeek API key or internet access.

---

## 1. Running the tests

### Unit tests

```powershell
.\gradlew.bat test                       # all variants
.\gradlew.bat testDebugUnitTest          # debug only
.\gradlew.bat test --tests "com.anaalarm.ai.PromptBuilderTest"
```

Report: `app/build/reports/tests/testDebugUnitTest/index.html`

### Instrumented tests

Start an emulator or plug in a device (`adb devices` must show one in state `device`), then:

```powershell
.\scripts\run-instrumented-tests.ps1
```

The script:

1. Builds and installs `app-debug.apk` and `app-debug-androidTest.apk`.
2. Grants `POST_NOTIFICATIONS` and `RECORD_AUDIO`, and allows the `SCHEDULE_EXACT_ALARM` and
   `USE_FULL_SCREEN_INTENT` app-ops.
3. Sets all three animation scales to 0 and dismisses the keyguard.
4. Runs the suite via `adb shell am instrument`.
5. Prints `SUCCESS: OK (145 tests)` or the failing stack traces.

Options:

```powershell
.\scripts\run-instrumented-tests.ps1 -Filter com.anaalarm.ui.HomeScreenInstrumentedTest
.\scripts\run-instrumented-tests.ps1 -Filter com.anaalarm.ui.HomeScreenInstrumentedTest#emptyStateExplainsWhatToDoNext
.\scripts\run-instrumented-tests.ps1 -SkipBuild
```

Raw output is kept in `adb-instrument.log`.

> **Why not `./gradlew connectedAndroidTest`?** AGP's Unified Test Platform reports results over
> gRPC+TLS, which local antivirus HTTPS interception breaks with *"Failed to receive the UTP test
> results"* — the tests pass on the device but the build still fails. `gradle.properties` sets
> `android.experimental.androidTest.useUnifiedTestPlatform=false`, and the script drives
> `am instrument` directly. If your machine has no TLS interception, `connectedAndroidTest`
> works too.

---

## 2. How the instrumented suite fakes the AI

Real conversations are exercised over the real Retrofit/OkHttp/serialization stack, against a
`MockWebServer` running inside the app process:

- `support/FakeAiServer.kt` speaks the DeepSeek Responses API — both the flat `output_text`
  shape and the nested `output[].content[]` envelope — and can also produce HTTP errors,
  in-body `error` objects, empty replies and dropped connections.
- `AnaAlarmApp.overrideAiBackend(client)` swaps the app's `DeepSeekClient` and
  `ConversationEngine` at runtime, so even `WakeUpActivity` talks to the fake.
- `app/src/debug/res/xml/network_security_config.xml` permits cleartext to `127.0.0.1` and
  `localhost` for the debug build only. The release policy is unchanged: no cleartext, system +
  user trust anchors.
- The server binds to an explicit IPv4 loopback address. Where `localhost` resolves to `::1`
  first, OkHttp burns a full connect timeout before falling back, turning fast tests into
  minute-long hangs.

Voice is not faked. TTS and speech recognition run against whatever the device provides, and the
tests use JUnit assumptions to skip (not fail) when a device has no engine.

---

## 3. Instrumented coverage map

### App container and manifest

| Class | Covers |
|---|---|
| `AnaAlarmAppInstrumentedTest` | Every DI singleton is constructed; the application scope is alive; both notification channels exist after startup and the legacy channel is gone; `recreateTts` / `recreateSpeech` swap instances; the AI backend override is reversible |
| `ManifestInstrumentedTest` | All ten permissions are declared; `MainActivity` is the launcher; `WakeUpActivity` is unexported, single-task, portrait, excluded from recents and has an empty task affinity; `AlarmService` is a `specialUse` foreground service; both receivers are registered; `FIRE_ALARM` resolves; application class, `minSdk` 26 and `targetSdk` 35 |

### Data layer

| Class | Covers |
|---|---|
| `data/AnaDatabaseInstrumentedTest` | Every DAO query against real SQLite: alarm ordering, conflict-replace upsert, enabled filtering, delete isolation, derived `timeMinutes`; message ordering, newest-first limit, per-session scoping and clearing; daily-log date uniqueness, `getByDate`, `getLatestBefore`; `clearAllTables` |
| `data/MemoryStoreInstrumentedTest` | Repository behaviour on the app's real database: alarm round-trip and in-place update, enable/disable, delete (including unknown ids), sorted flow, chronological history with limit and session scoping, today/yesterday summaries including the blank-log and most-recent-earlier-day rules |
| `data/SettingsStoreInstrumentedTest` | DataStore persistence: factory defaults, full round-trip, partial updates, BOM/whitespace stripping on pasted keys, name trimming, blank-entry filtering in lists, comma-separated UI input, flow emission, durability across store instances, `SettingsLists` format |

### Alarm subsystem

| Class | Covers |
|---|---|
| `alarm/AlarmSchedulerInstrumentedTest` | Scheduling verified through the system's `nextAlarmClock` record — the thing that actually wakes the device: schedule registers, cancel removes, a disabled alarm cancels instead, `rescheduleAll` and `rescheduleNext` re-arm from the database (and tolerate unknown ids), plus next-fire-time and bitmask rules |
| `alarm/NotificationsInstrumentedTest` | Channel creation is idempotent; the alarm channel is high-importance with an alarm-usage ringtone and vibration; the session channel is silent and low; DND bypass and lock-screen visibility are requested and honoured wherever the system permits; the legacy channel is deleted; both notification payloads carry the right channel, category, ongoing flag and full-screen intent; `wakeUpIntent` flags and extras |
| `alarm/BootReceiverInstrumentedTest` | `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` re-arm stored alarms; unrelated broadcasts and disabled alarms do not |
| `alarm/AlarmFiringInstrumentedTest` | The full path with UI Automator: broadcast → receiver → foreground service → wake-up screen over the lock screen, with the alarm re-armed for its next occurrence; the Stop button closes a session started by an alarm |

### AI layer

| Class | Covers |
|---|---|
| `ai/DeepSeekClientInstrumentedTest` | No request is made without a key; both response shapes parse; the request carries the bearer token, model, instructions, token cap and turn list; 401/403 map to "invalid api key", other statuses keep their code, in-body errors surface their message, empty replies fail; a dropped connection is retried; an unreachable server reports a network error; keys are masked in logs |
| `ai/ConversationEngineInstrumentedTest` | Greeting and session state; instructions carry persona, name, language directive, habits, interests, session length, formatted date and yesterday's log; Portuguese switches the directive; `respond` before `startSession` is rejected; history accumulates correctly across turns; `wrapUp` sends the farewell prompt; `endSession` writes the day log and resets, and is a no-op without a session; re-entering a live session does not rebuild the prompt; backend errors propagate mapped |

### UI

| Class | Covers |
|---|---|
| `ui/HomeScreenInstrumentedTest` | Empty state; stored alarms replace it; the switch disables and re-enables an alarm in the database; delete confirmation with both cancel and confirm paths; tapping a card opens the editor pre-filled; add opens the editor and cancel returns; settings navigation round-trip; disabled alarms stay listed but switched off |
| `ui/AlarmEditScreenInstrumentedTest` | Saving a new alarm persists it and returns home; cancel discards; repeat chips map to the Sunday-first bitmask; editing updates the same row rather than inserting; stored snooze is preserved; editing a disabled alarm does not silently enable it; the saved alarm appears on the home list |
| `ui/SettingsScreenInstrumentedTest` | Defaults on a fresh install; saving writes every field to DataStore; values are pre-filled on reopen; the language choice is remembered; leaving without saving discards; the API key is sanitised; the session-length section reflects stored state |
| `ui/LocalizationInstrumentedTest` | Every string resolves non-blank in `en` and `pt-BR`; every user-facing string is actually translated (with an explicit shared-by-design allowlist); format placeholders survive translation; key wording; error messages name the recovery action |
| `ui/wakeup/WakeUpSessionInstrumentedTest` | The screen renders clock and Stop immediately; no API key produces the localized hint and an ended session; Ana greets and answers a reply end to end; a stop phrase triggers the wrap-up request; the Stop button finishes the activity and writes the day log; a backend failure is displayed instead of crashing; yesterday's log is fed back into the morning prompt |
| `ui/wakeup/SessionPhrasesInstrumentedTest` | English and Portuguese stop phrases, ordinary conversation that must not end the session, case/padding insensitivity, and self-consistency of the configured list |

### Voice

| Class | Covers |
|---|---|
| `voice/TtsManagerInstrumentedTest` | Blank text completes immediately; the engine reports readiness; **speaking always calls back** (the watchdog guarantee that stops a session hanging); utterances queued before initialisation are flushed; language/pitch/rate values are clamped and accepted for both languages; stop and shutdown are idempotent; the engine still speaks after being stopped |
| `voice/SpeechListenerInstrumentedTest` | A fresh listener is idle; availability is reported without throwing; a missing recognizer produces `ERROR_CLIENT` rather than a crash; start/stop returns cleanly to idle where a recognizer exists; stop and destroy are safe before anything starts; every error code has a readable name; the app can recreate the listener |

---

## 4. Unit test coverage map

| Class | Covers |
|---|---|
| `alarm/AlarmTriggerCalculatorTest` | Next-trigger arithmetic across all repeat-day combinations and day rollovers |
| `ai/PromptBuilderTest` | System-prompt composition from profile and context |
| `ai/ResponseTextExtractorTest` | Both Responses API payload shapes and the null/blank cases |
| `ai/ApiErrorMapperTest` | Throwable → `ApiException` mapping, including nested certificate-trust failures |
| `ai/DeepSeekClientTest` | Request/response contract over MockWebServer on the JVM |
| `ai/ConversationEngineTest` | Session orchestration with a mocked client |
| `data/MemoryStoreTest` | Repository logic with fakes |
| `data/SettingsListsTest`, `data/SettingsStoreSanitizeTest` | List join/split and secret sanitising |
| `data/AlarmEntityTest` | Derived `timeMinutes` |
| `ui/home/HomeViewModelTest` | Toggle/delete side effects on the scheduler and store |
| `ui/wakeup/SessionPhrasesTest` | Stop-phrase matching |

---

## 5. Manual QA checklist

Automation cannot confirm that a phone in your bedroom actually wakes you. Run this before a
release, ideally spanning a real night.

> **Before you start:** device connected with USB debugging, app installed, API key added, and
> notification + microphone + exact-alarm permissions granted.

### 5.1 First launch and permissions
- [ ] App opens to Home with the warm morning theme and the "AnaAlarm" top bar.
- [ ] Notification permission prompt appears (Android 13+).
- [ ] Microphone permission prompt appears.
- [ ] Home shows "No alarm set" when the list is empty.
- [ ] With exact-alarm permission missing, the warning card appears, **Grant permission** opens
      the system screen, and the card disappears after granting (re-open the app).
- [ ] On Android 14+, the full-screen-intent card behaves the same way.

### 5.2 Alarms
- [ ] Add an alarm 1–2 minutes ahead → card shows the correct `HH:mm`.
- [ ] Repeat days: select Mon/Wed → the card shows those day labels and only those days fire.
- [ ] One-shot (no days): fires once, then re-arms for the next day (intended — use the switch
      to stop it).
- [ ] Snooze slider persists across save → edit.
- [ ] Toggle disables/enables; the disabled card is dimmed.
- [ ] Delete shows a confirmation; the confirmed alarm disappears.
- [ ] Edit pre-fills, and changing the time updates the card.
- [ ] **Fire test:** screen off and locked → the wake-up screen appears over the lock screen.
- [ ] **Reboot test:** set a repeating alarm → reboot → it still fires.
- [ ] **Doze test:** set an alarm, leave the phone idle 30+ minutes → it still fires on time.

### 5.3 Settings
- [ ] API key is masked, and persists across an app restart.
- [ ] Name, habits and interests show up in the conversation.
- [ ] Language change is reflected in the TTS voice and Ana's reply language.
- [ ] Session length persists.
- [ ] "Settings saved" appears on Save.

### 5.4 Wake-up session, happy path
- [ ] Full-screen dark UI, large live clock, status pill starts at "Starting…" then "Speaking…".
- [ ] Ana greets you by name, in the configured language.
- [ ] The pill switches to "Listening…" and the mic picks up your voice.
- [ ] Your transcript appears under "You said", then "Thinking…", then a 1–2 sentence reply.
- [ ] She runs at least one quiz and gives the answer.
- [ ] She asks about a configured habit.
- [ ] She references yesterday's log (run a session, promise something, check the next one).
- [ ] A stop phrase produces a short farewell and closes the screen.
- [ ] The **Stop** button closes immediately.
- [ ] With a 5-minute session length, she wraps up on her own around the 5-minute mark.

### 5.5 Wake-up session, edge cases
- [ ] **Silence:** say nothing for two listen cycles → she asks you to repeat, then offers typing.
- [ ] **No API key:** localized error, session ends gracefully.
- [ ] **Airplane mode:** network error message, no crash.
- [ ] **Mic revoked:** the text fallback appears and typing works end to end.
- [ ] **Language mid-session:** replying in the other language does not derail her.
- [ ] **Rotation:** the screen stays portrait; no restart mid-conversation.
- [ ] **Incoming call during a session:** audio focus is released and the app recovers.

### 5.6 Memory
- [ ] Force-kill mid-session → no crash on next launch.
- [ ] After a normal session, `daily_logs` contains today's summary.
- [ ] Next morning, the prompt includes yesterday's context and she asks about it.

### 5.7 Localization
- [ ] Full pt-BR review with the device language set to Portuguese: Home, editor, Settings,
      wake-up screen, dialogs.
- [ ] TTS speaks pt-BR (the device needs a pt-BR voice installed).
- [ ] Recognition listens in pt-BR.

### 5.8 Performance and battery
- [ ] No ANR or crash during a full 10-minute session.
- [ ] The screen stays awake for the whole session.
- [ ] No memory growth after hours in the background (`adb logcat`, no `OutOfMemory`).

---

## 6. Useful adb commands

```powershell
adb devices -l                                              # connected devices
adb install -r app\build\outputs\apk\debug\app-debug.apk    # install
adb logcat -s AnaAlarm:V AnaSession:V AnaTts:V AnaSpeech:V  # app logs only
adb shell dumpsys alarm | Select-String anaalarm            # scheduled alarms
adb shell cmd appops get com.anaalarm SCHEDULE_EXACT_ALARM  # exact-alarm state
adb shell cmd appops get com.anaalarm USE_FULL_SCREEN_INTENT
adb shell run-as com.anaalarm ls databases/                 # verify the Room database
adb shell am broadcast -a com.anaalarm.action.FIRE_ALARM `
    -n com.anaalarm/.alarm.AlarmReceiver --el extra_alarm_id 1   # fire an alarm now (debug builds)
adb shell pm clear com.anaalarm                             # factory-reset the app
```

---

## 7. Writing new instrumented tests

Helpers live in `app/src/androidTest/java/com/anaalarm/support/`:

| Helper | Use |
|---|---|
| `TestEnv` | `app`, `context`, `alarmManager`, `clearDatabase()`, `resetSettings()`, `cancelAllScheduledAlarms()`, `waitUntil { }`, `pollFor { }` |
| `ComposeSupport` | `str(R.string.x)` for locale-independent assertions, `awaitText`, `awaitTextGone`, `awaitTextField`, `hasText`, `hasTextField` |
| `Screens` | `launchHome()`, `launchWakeUp()`, `runtimePermissions()` |
| `FakeAiServer` | Local DeepSeek stand-in plus `installIntoApp()` |

Conventions worth keeping:

- **Assert against string resources, not English literals**, so the suite survives a device
  language change.
- **Seed state before launching the activity.** Use `createEmptyComposeRule()` with
  `Screens.launchHome()` rather than an activity rule, which would compose before `@Before` runs.
- **Always clean up AlarmManager, not just the database.** Pending alarms outlive Room, and an
  earlier alarm hides a later one in `nextAlarmClock`. `TestEnv.clearDatabase()` handles both.
- **Poll instead of sleeping.** `AlarmManager`, the application coroutine scope and DataStore all
  settle asynchronously.
- **Use `Assume` for hardware you cannot guarantee** (a TTS engine, a speech recognizer, granted
  app-ops) so a limited emulator skips rather than fails.
