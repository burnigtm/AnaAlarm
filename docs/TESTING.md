# Testing

AnaAlarm ships two automated suites plus a manual checklist for the things only a human with a
real phone can confirm.

| Suite | Location | Size | Runtime | Needs a device? |
|---|---|---|---|---|
| JVM unit tests | `app/src/test` | See the generated Gradle report | ~20 s | No |
| Instrumented tests | `app/src/androidTest` | See the device verdict | ~3 min per API | Yes |
| Manual checklist | this document, §5 | Roughly 50 checks | ~30 min | Yes, ideally overnight |

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
   `USE_FULL_SCREEN_INTENT` app-ops. CI reads required app-ops back and fails before tests unless
   the applicable API reports `allow`.
3. Sets all three animation scales to 0 and dismisses the keyguard.
4. Runs the suite via `adb shell am instrument`.
5. Prints the device-reported `OK (<count> tests)` verdict or the failing stack traces.

Options:

```powershell
.\scripts\run-instrumented-tests.ps1 -Filter com.anaalarm.ui.HomeScreenInstrumentedTest
.\scripts\run-instrumented-tests.ps1 -Filter com.anaalarm.ui.HomeScreenInstrumentedTest#emptyStateExplainsWhatToDoNext
.\scripts\run-instrumented-tests.ps1 -SkipBuild
```

Raw output is kept in `adb-instrument.log`.

> The script uses `am instrument` directly for predictable device output. `connectedAndroidTest`
> remains available when the host/device test transport is reliable.

Linux and CI use the equivalent helper (the API argument enables capability-aware grants):

```bash
bash scripts/ci/run-instrumented-tests.sh 36
```

GitHub Actions on the **CI mirror** exposes three quality checks plus one gated distribution
check. Origin pushes do not run them until the same commit is pushed to GitHub
(`.\scripts\push-ci-mirror.ps1`). Host checks run
unit tests, compile Android tests, build debug and minified release APKs, and lint every variant.
They also build the internal variant and rehearse the real packaging/verifier with a disposable
one-day key, so pull requests test the distribution path without receiving the stable key.
The device matrix boots API 26 and API 36 emulators and executes the complete instrumented suite
on each. API 36 also uses one-day disposable keys to sign, verify, install, and cold-launch both
the minified production-shaped release APK and the exact `com.anaalarm.internal` variant. It
checks both processes stay alive, then deletes the keys and signed APKs. A successful workflow
run—not this configuration alone—is the execution evidence.

Before the aggregate device suite, CI runs the alarm scheduler, boot receiver, notification, and
end-to-end firing classes as a required group. That group must report exactly 34 tests and exactly
zero skips on both API 26 and API 36. CI then clears both app packages, restores and verifies the
required capabilities, and executes the complete 173-test aggregate suite from clean app data.
That phase must also run exactly 173 tests with zero skips on the fixed Google APIs images.
Unexpected assumptions/ignores, a missing exact-alarm grant, a missing full-screen grant, or a
zero/partial runner invocation therefore fails closed. The locked-boot cases cancel only their
regular PendingIntent, preserving the device-protected mirror and exercising the real API-26+
recovery path.

```text
Host:   test + compile androidTest -> builds -> ephemeral packaging rehearsal -> lint --+
API 26: required + aggregate instrumented suites --------------------------------------+--> Installable APK
API 36: suites -> ephemeral release + exact internal install/launch --------------------+
```

`Installable APK` runs only after all three quality checks pass on a trusted `main` push or
manual-main run and the signing environment's approval gate is satisfied. Its fresh runner
downloads the Host-built unsigned input and runs no Gradle or build task before the reviewed
signing helper executes. It publishes a stable-key, minified
`com.anaalarm.internal` APK for 30 days and never exposes signing secrets to pull requests. The
complete trigger, artifact, installation, checksum, signing, and rotation contract is documented in
[`CI_AND_INSTALLABLE_BUILDS.md`](CI_AND_INSTALLABLE_BUILDS.md).

Every CI dependency resolution uses strict checksum verification and dependency locks. See
[`SUPPLY_CHAIN.md`](SUPPLY_CHAIN.md) before updating Gradle dependencies or workflow actions.

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
  `localhost` for the debug build only. Release permits no cleartext and trusts system CAs only.
- The server binds to an explicit IPv4 loopback address. Where `localhost` resolves to `::1`
  first, OkHttp burns a full connect timeout before falling back, turning fast tests into
  minute-long hangs.

Voice output and recognition still run against whatever the device provides. One negative-path
test injects an unavailable-recognizer capability so the client-error contract is deterministic;
it does not fake recognition results. Local or physical-device runs may use JUnit assumptions for
genuinely absent TTS/recognizer hardware. CI deliberately uses fixed `google_apis` API 26 and API
36 images and rejects every skipped aggregate test, so a changed/missing engine is visible as a
release-gate failure rather than a green no-op.

---

## 3. Instrumented coverage map

### App container and manifest

| Class | Covers |
|---|---|
| `AnaAlarmAppInstrumentedTest` | Every DI singleton is constructed; the application scope is alive; both notification channels exist after startup and the legacy channel is gone; `recreateTts` / `recreateSpeech` swap instances; the AI backend override is reversible |
| `ManifestInstrumentedTest` | Required permissions and optional microphone hardware; launcher and private lock-screen activity; `systemExempted` alarm-continuation service; registered receivers; debug-only manual-fire action; application class, `minSdk` 26 and `targetSdk` 36 |

### Data layer

| Class | Covers |
|---|---|
| `data/AnaDatabaseInstrumentedTest` | Every DAO query against real SQLite, including raw-message cutoff deletion and session scoping |
| `data/AnaDatabaseRetentionInstrumentedTest` | Reopening a real Room database invokes the production `onOpen` callback, removes expired messages, and preserves the exact cutoff and newer rows |
| `data/AnaDatabaseMigrationInstrumentedTest` | `MigrationTestHelper` creates the actual exported v2 schema, validates v2→current, preserves all three entity types, and verifies both history/retention indexes |
| `data/MemoryStoreInstrumentedTest` | Repository behaviour on the app's real database: alarm round-trip and in-place update, enable/disable, delete (including unknown ids), sorted flow, chronological history with limit and session scoping, bounded daily logs, and most-recent-earlier-day lookup |
| `data/SettingsStoreInstrumentedTest`, `data/SettingsStoreRecoveryInstrumentedTest` | Encrypted-key DataStore round-trip plus settings defaults, partial updates, sanitizing, lists, durability, legacy-plaintext migration, corrupt ciphertext fail-closed behavior, and invalidated-key reset/retry |
| `data/KeystoreSecretCipherInstrumentedTest` | Keystore AES-GCM ciphertext does not embed plaintext, round-trips, rejects malformed payloads, and proves old ciphertext fails closed after key loss while a replacement key works |

### Alarm subsystem

| Class | Covers |
|---|---|
| `alarm/AlarmSchedulerInstrumentedTest` | Fail-closed exact-alarm capability plus system `nextAlarmClock` registration/cancellation, typed outcomes, database reconciliation, exact snooze duration and trigger rules |
| `alarm/NotificationsInstrumentedTest` | Fail-closed full-screen capability; channel creation is idempotent; the alarm channel is high-importance with an alarm-usage ringtone and vibration; the session channel is silent and low; DND bypass and lock-screen visibility are requested and honoured wherever the system permits; the legacy channel is deleted; both notification payloads carry the right channel, category, ongoing flag and full-screen intent; `wakeUpIntent` flags and extras |
| `alarm/BootReceiverInstrumentedTest` | Fail-closed exact-alarm capability; boot/package/time/time-zone/permission actions reconcile stored alarms; unrelated broadcasts and disabled alarms do not |
| `alarm/AlarmFiringInstrumentedTest` | Fail-closed exact-alarm/UI assertions across foreground app + debug broadcast → receiver → foreground service → wake UI; one-shot disable/repeat re-arm; AI failure keeps the service active until Stop/Snooze |

### AI layer

| Class | Covers |
|---|---|
| `ai/DeepSeekClientInstrumentedTest` | Auth/request contract including reasoning-off and 96-token cap; response shapes/errors; bounded transport behavior and redacted key reporting |
| `ai/ConversationEngineInstrumentedTest` | Prompt/session/history contract, stable greeting prefix, wrap-up, durable daily log and raw-session clearing |

### UI

| Class | Covers |
|---|---|
| `ui/HomeScreenInstrumentedTest` | Empty state; stored alarms replace it; the switch disables and re-enables an alarm in the database; delete confirmation with both cancel and confirm paths; tapping a card opens the editor pre-filled; add opens the editor and cancel returns; settings navigation round-trip; disabled alarms stay listed but switched off |
| `ui/AlarmEditScreenInstrumentedTest` | Saving a new alarm persists it and returns home; cancel discards; repeat chips map to the Sunday-first bitmask; editing updates the same row rather than inserting; stored snooze is preserved; editing a disabled alarm does not silently enable it; the saved alarm appears on the home list |
| `ui/SettingsScreenInstrumentedTest` | Defaults on a fresh install; saving writes every field to DataStore; values are pre-filled on reopen; the language choice is remembered; leaving without saving discards; the API key is sanitised; the session-length section reflects stored state |
| `ui/LocalizationInstrumentedTest` | Every string resolves non-blank in `en` and `pt-BR`; every user-facing string is actually translated (with an explicit shared-by-design allowlist); format placeholders survive translation; key wording; error messages name the recovery action |
| `ui/wakeup/WakeUpSessionInstrumentedTest` | Clock/Stop, Snooze only for real alarm ids, overlapping-alarm replacement, controller retention across recreation, no-key/backend failures, full fake-AI turns, stop/wrap-up persistence and prior-day context |
| `ui/wakeup/SessionPhrasesInstrumentedTest` | English and Portuguese stop phrases, ordinary conversation that must not end the session, case/padding insensitivity, and self-consistency of the configured list |

### Voice

| Class | Covers |
|---|---|
| `voice/TtsManagerInstrumentedTest` | Bounded readiness, queued speech completion, clamped configuration and idempotent stop/shutdown; the pure registry suite covers callback identity/order |
| `voice/SpeechListenerInstrumentedTest` | Availability, lifecycle, error naming, safe start/stop/destroy and recreation; pure generation tests cover stale callback rejection |

---

## 4. Unit test coverage map

| Class | Covers |
|---|---|
| `alarm/AlarmTriggerCalculatorTest` | Next-trigger arithmetic across all repeat-day combinations and day rollovers |
| `alarm/AlarmSchedulerTest`, `alarm/AlarmReceiverTest` | Permission-denied scheduling remains explicitly covered alongside typed failures, private release action, distinct snooze PendingIntent, cancellation scope and post-fire routing |
| `alarm/DirectBootAlarmStoreTest`, `alarm/BootReceiverTest` | Device-protected snapshot validation/tombstones and locked/unlocked broadcast routing |
| `alarm/PlaybackPreparationGateTest` | Stop/restart and reverse-completion races reject stale ringtone players and release replacements before ownership changes |
| `ai/PromptBuilderTest` | System-prompt composition from profile and context |
| `ai/ResponseTextExtractorTest` | Both Responses API payload shapes and the null/blank cases |
| `ai/ApiErrorMapperTest` | Throwable → `ApiException` mapping, including nested certificate-trust failures |
| `ai/DeepSeekClientTest` | Request/response contract over MockWebServer on the JVM |
| `ai/ConversationEngineTest` | Session orchestration with a mocked client |
| `data/MemoryStoreTest` | Repository logic with fakes |
| `data/AnaDatabaseMigrationTest` | Host-side real SQLite 1→7 preservation and current-index validation |
| `data/SettingsListsTest`, `data/SettingsStoreSanitizeTest` | List join/split and secret sanitising |
| `data/AlarmEntityTest` | Derived `timeMinutes` |
| `ui/home/HomeViewModelTest` | Toggle/delete side effects on the scheduler and store |
| `ui/wakeup/SessionPhrasesTest` | Stop-phrase matching |
| `telemetry/LatencyMetricsTest`, `ai/DeepSeekClientTest` telemetry cases | Stable metric schema/sanitization, call-scoped capture, exactly-once alarm/TTS boundaries, and one model terminal event across success, retry, provider failure, deadline, and caller cancellation |
| `voice/RecognitionGenerationTest`, `voice/TtsRequestGenerationTest`, `voice/UtteranceRegistryTest` | Stale/cancelled ASR and TTS request/callback rejection |

Enhancement-program additions (see [ENHANCEMENTS.md](ENHANCEMENTS.md)):

| Class | Covers |
|---|---|
| `ai/stream/SseParserTest` | Frame splitting, multi-line data joins, keep-alive comments, CRLF/CR chunk boundaries, size caps, trailing dispatch |
| `ai/stream/ResponsesStreamDecoderTest` | Sequence validation, event/type matching, `[DONE]` rejection, terminal semantics, unknown-event tolerance |
| `ai/stream/PhraseSegmenterTest` | 24/72/160-char rules, surrogate safety, decimals, whitespace-only deltas, tail flush |
| `ai/stream/StreamingTurnCoordinatorTest` | Flush-then-append ordering, bounded backpressure, settle gating, late/duplicate callbacks, cancellation, fallback gating |
| `ai/DeepSeekClientStreamTest` | SSE contract over MockWebServer: happy path with usage, EOF-without-terminal, failed/incomplete terminals, protocol and content-type failures, cancellation |
| `alarm/DismissalChallengesTest` | Math ranges/self-consistency, memory codes, answer tolerance, type-code fallback |
| `data/StreakCalculatorTest` | Consecutive-day chains, today-pending behavior, gaps/unmarks, independence, bad dates |
| `data/DataExportTest` | Structural credential exclusion, encrypted round trip, damaged-file rejection, merge restore (fake cipher; Robolectric has no KeyStore) |
| `data/PronounsTest`, `ui/AppLocalesTest` | Pronoun form mapping and BCP-47 tag mapping |
| `voice/OrderedUtteranceRegistryTest` | Ordered per-turn registry: start/finish round trips, per-turn clearing, audibility counts |
| `ai/DebugTlsTest` | Debug TLS hook remains a strict pass-through |

---

## 5. Manual QA checklist

The automated alarm-firing test intentionally keeps the app foreground and uses the debug
broadcast action; it does not lock/sleep a physical device or prove speaker audibility. The
screen-off/locked, airplane-mode/no-key test below is therefore a release-blocking physical-device
gate, not something the host-side suite claims to cover.

Automation cannot confirm that a phone in your bedroom actually wakes you. Run this before a
release, ideally spanning a real night.

> **Before you start:** device connected with USB debugging, app installed, API key added, and
> notification + microphone + exact-alarm permissions granted.

### 5.1 First launch and permissions
- [ ] App opens to Home with the warm morning theme and the "AnaAlarm" top bar.
- [ ] Notification permission prompt appears (Android 13+).
- [ ] Microphone permission prompt appears.
- [ ] Home shows a **Next alarm** card when at least one alarm is enabled, and hides it when
      the list is empty or every alarm is disabled.
- [ ] With exact-alarm permission missing, the warning card appears, **Grant permission** opens
      the system screen, and the card disappears when the app resumes after granting.
- [ ] On Android 14+, the full-screen-intent card behaves the same way.

### 5.2 Alarms
- [ ] Add an alarm 1–2 minutes ahead → card shows the correct `HH:mm`.
- [ ] Repeat days: select Mon/Wed → the card shows those day labels and only those days fire.
- [ ] One-shot (no days): fires once and becomes disabled after delivery.
- [ ] Snooze: tap the alarm-session action, confirm the current UI closes, and the distinct exact
      alarm fires after the configured number of minutes.
- [ ] Snooze slider persists across save → edit.
- [ ] Toggle disables/enables; the disabled card is dimmed.
- [ ] Delete shows a confirmation; the confirmed alarm disappears.
- [ ] Edit pre-fills, and changing the time updates the card.
- [ ] **Fire test:** screen off and locked → the wake-up screen appears over the lock screen.
- [ ] **Locked-boot repeating test:** set a repeating alarm → reboot without unlocking → it still
      fires using only the device-protected mirror, then reconciles with Room after unlock.
- [ ] **Locked-boot one-shot/Snooze test:** before first unlock, a one-shot fires only once while
      repeated Snooze actions keep the configured interval; Stop prevents any later re-arm.
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
- [ ] She references the most recent prior-day log (run a session, promise something, then check
      a later session—even after skipping a day).
- [ ] A stop phrase produces a short farewell and closes the screen.
- [ ] The **Stop** button closes immediately.
- [ ] With a 5-minute session length, she wraps up on her own around the 5-minute mark.

### 5.5 Wake-up session, edge cases
- [ ] **Silence:** say nothing for two listen cycles → she asks you to repeat, then offers typing.
- [ ] **No API key:** localized error appears while local sound/vibration keeps running; Stop and
      Snooze each silence it and complete their action.
- [ ] **Airplane mode:** network error message, no crash, and local sound remains active until an
      explicit Stop/Snooze.
- [ ] **Mic revoked:** the text fallback appears and typing works end to end.
- [ ] **Language mid-session:** replying in the other language does not derail her.
- [ ] **Rotation/fold:** on API 36 large-screen and foldable configurations, the same controller,
      alarm id, and conversation remain active without a restart.
- [ ] **Incoming call during a session:** audio focus is released and the app recovers.

### 5.6 Memory
- [ ] Force-kill mid-session → no crash on next launch.
- [ ] After a normal session, `daily_logs` contains today's bounded role-labelled transcript.
- [ ] A later session includes the most recent prior-day context and follows up on it.

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
adb shell am broadcast -a com.anaalarm.action.DEBUG_FIRE_ALARM `
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
- **Use `Assume` only for genuinely optional hardware or an inapplicable platform version** (for
  example, a TTS engine or speech recognizer). Required alarm/full-screen app-ops must assert and
  fail closed; permission-denied behavior belongs in deterministic scheduler unit tests.
