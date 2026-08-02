# Architecture

This document describes how AnaAlarm is structured, how the pieces fit together, and the runtime lifecycle of the app.

## 1. Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         UI layer (Compose)                       │
│  HomeScreen │ AlarmEditScreen │ SettingsScreen │ WakeUpScreen   │
└───────────────┬──────────────┬──────────────────┬───────────────┘
                │              │                  │
                │ MVVM         │                  │ SessionController
                ▼              ▼                  ▼
        ┌────────────┐  ┌────────────┐   ┌────────────────────┐
        │ Home/      │  │ Settings   │   │ ConversationEngine │
        │ AlarmEdit  │  │ (direct    │   │        +           │
        │ ViewModels │  │  DataStore │   │ TtsManager/Speech  │
        └─────┬──────┘  │  writes)   │   └─────────┬──────────┘
              │         └────────────┘             │
              ▼                                    ▼
        ┌──────────────┐                  ┌──────────────────┐
        │ MemoryStore  │                  │   DeepSeekClient │
        │  (Room +     │                  │  (Retrofit/OKHttp│
        │   settings)  │                  │   SSE-ready)     │
        └──────────────┘                  └────────┬─────────┘
                                                  ▼
                                    https://api.deepseek.com/responses
                                         model: deepseek-v4-flash
```

## 2. Modules and responsibilities

### `data/` — persistence
| File | Responsibility |
|---|---|
| `Entities.kt` | Room entities: `AlarmEntity`, `MessageEntity`, `DailyLogEntity` |
| `Daos.kt` | Room DAOs with Flow-based observation for alarms |
| `AnaDatabase.kt` | Singleton Room database (`anaalarm.db`) |
| `SettingsStore.kt` | DataStore preferences (`anaalarm_settings`); exposes `Flow<AppSettings>`, atomic updates, and legacy-key migration |
| `KeystoreSecretCipher.kt` | AES-GCM credential encryption backed by an app-private Android Keystore key |
| `MemoryStore.kt` | Alarms CRUD, session/message management, daily logs, raw-message clearing and retention pruning |

### `ai/` — the brain
| File | Responsibility |
|---|---|
| `DeepSeekClient.kt` | Retrofit Responses API client; 20-second operation deadline, bounded retry, structured status/usage parsing, redacted telemetry |
| `PromptBuilder.kt` | Builds the `instructions` system prompt from profile + date/time + the most recent prior-day context |
| `ConversationEngine.kt` | Orchestrates a session: starts it, sends user turns with history, wraps up, persists the daily log |

### `voice/` — spoken conversation
| File | Responsibility |
|---|---|
| `TtsManager.kt` | Bounded TTS initialization plus utterance-ID-scoped start/completion/error/watchdog callbacks |
| `SpeechListener.kt` | Generation-scoped Android recognition; on-device preference, default fallback, partial/final/error callbacks |

### `alarm/` — waking you up
| File | Responsibility |
|---|---|
| `AlarmScheduler.kt` | Typed `setAlarmClock` scheduling results, regular/snooze cancellation, one-shot disable, repeat computation and reconciliation |
| `AlarmReceiver.kt` | Private scheduled-delivery receiver; starts the service and keeps async post-fire persistence alive |
| `AlarmService.kt` | Foreground delivery, immediate local sound/vibration, screen wake and full-screen activity launch |
| `BootReceiver.kt` | Reconciles enabled alarms after boot, update, clock/time-zone change and exact-alarm permission grant |
| `Notifications.kt` | Notification channel creation |

### `ui/` — screens
| File | Responsibility |
|---|---|
| `AppRoot.kt` | Screen navigation state (Home / AlarmEdit / Settings) |
| `home/` | Home screen + `HomeViewModel` (alarms list, delete/toggle, test-session button, permission warning) |
| `alarm/AlarmEditScreen.kt` | Time picker, repeat-day chips, snooze slider, save/cancel |
| `settings/SettingsScreen.kt` | API key, name, language, habits, interests, session length |
| `wakeup/` | `WakeUpActivity` (full-screen), `SessionController` (conversation state machine), `WakeUpScreen` (UI) |
| `theme/Theme.kt` | Material 3 color scheme (light + dark), warm morning palette |

### `AnaAlarmApp.kt` — manual DI
The `Application` eagerly creates only the storage/scheduler path needed for alarm reconciliation.
Retrofit/OkHttp, TTS, speech recognition, and the shared test `ConversationEngine` are initialized
lazily so a killed-process alarm can reach `AlarmService` without paying those cold-start costs.
Each live wake controller receives its own `ConversationEngine`; network and storage dependencies
remain shared. `applicationScope` (IO dispatcher + SupervisorJob) serves receivers and durable
finalization.

## 3. Thread model

- **Compose state** must be mutated on the **main thread**.
- **TTS callbacks** are posted to the main thread and matched to the active utterance id.
- **Speech callbacks** are posted to the main thread and accepted only for the active recognition generation.
- **Network + Room calls** run on `Dispatchers.IO` (Retrofit suspend + Room suspend are safe from any dispatcher; `applicationScope` is IO).
- `SessionController` runs in `WakeUpSessionViewModel.viewModelScope`; the controller therefore
  survives rotation/fold/large-screen configuration changes.
- `SettingsStore` maps DataStore on IO and caches the decrypted credential in memory, atomically
  refreshing it after a settings save.

`SessionController` owns cancellable startup/turn/listen/wrap-up jobs. Atomic one-shot guards make
activity finish, alarm-stop, and conversation finalization idempotent. `WakeUpSessionViewModel`
replaces the controller explicitly for a different overlapping alarm id; final persistence moves
to the application scope and uses the retired controller's isolated engine.

## 4. Wake-up session lifecycle

```
WakeUpActivity.onCreate
        │
        ▼
SessionController.start()
  ├─ read settings (language, session minutes)
  ├─ wire SpeechListener generation-scoped callbacks
  ├─ prepare recognizer (prefer on-device)
  ├─ configure/await TTS ─────────────────┐
  └─ beginSession() concurrently:          │
        ConversationEngine.startSession()
          ├─ sessionId = now (ms)
          ├─ instructions = PromptBuilder.build(profile, date/time, prior-day log)
          ├─ persist synthetic greeting input
          └─ POST /responses ──► greeting text
                                           │
      speak(greeting) ◄─────────────────────┘
        ├─ onStart ──► stop local alarm fallback
        └─ onDone ──► 175 ms settle ──► LISTENING ──► startListening()
        │
        ├── user speaks ──► onUserSpeech(text)
        │     ├─ stop phrase? ──► wrapUp()
        │     └─ else ──► ConversationEngine.respond(text)
        │                   ├─ history (last 20) + new message saved
        │                   ├─ POST /responses (full input)
        │                   └─ speak(reply) ──► listen again
        │
        ├── recognizer partial ──► visible partial transcript
        ├── soft error/silence ──► one retry, then typed fallback
        ├── hard error ──────────► typed fallback immediately
        │
        └── time up (elapsed >= sessionMinutes) ──► wrapUp()
              └─ farewell = POST /responses ("wrap up" prompt)
                 speak(farewell) ──► endSession()
                     ├─ activity finish (once)
                     └─ application-scope finalization
                         ├─ save daily log (last 40 messages, 900 chars)
                         └─ delete raw session rows after success
```

**End conditions (all paths):**
1. Stop phrase spoken (see `stopPhrases` in `SessionController`).
2. Session time elapses → AI wraps up with a farewell.
3. Stop button pressed → immediate stop, log saved.
4. Snooze button → exact one-off schedule succeeds, then current session stops.
5. Unrecoverable AI error → conversation ends and an actionable error remains visible, but the
   independent local alarm continues until explicit Stop or successful Snooze.

## 5. Data model

```
alarms(id PK, hour, minute, days bitmask, snoozeMinutes, enabled)
messages(id PK, sessionId, role "user"|"assistant", content, timestamp)
daily_logs(id PK, date "yyyy-MM-dd", summary)

DataStore keys: api_key_encrypted, name, language, habits, interests,
                session_minutes, snooze_minutes
```

- `days` bitmask: bit 0 = Sunday … bit 6 = Saturday.
- `sessionId` is a process-monotonic millisecond value; an atomic increment prevents overlapping
  sessions created in the same clock tick from sharing message rows.
- Daily log: up to 900 characters of role-labelled session history, appended (not blank-replaced)
  to today's bounded log. The most recent log before today is injected into the prompt.
- Raw session messages are deleted after a successful daily log and startup-pruned after seven days.
- Room version 4 has explicit 1→2→3→4 migrations, exported schemas, a
  `(sessionId, timestamp)` history index, and a timestamp-only retention index.

## 6. Error handling strategy

| Failure | Where | Handling |
|---|---|---|
| No API key | `DeepSeekClient` | Throws `ApiException("no api key")` → localized message on wake-up screen |
| Slow model/network | `DeepSeekClient` | Whole logical operation ends at 20 seconds with a localized network error |
| Quick retryable I/O failure | `DeepSeekClient` | One retry within the same deadline; TLS/timeouts/cancellation are not retried |
| HTTP/API error | `DeepSeekClient` | Structured `failed`/`incomplete`/HTTP state becomes `ApiException` |
| Empty response | `DeepSeekClient` | `ApiException("empty response")` |
| Speech error / silence | `SpeechListener` + `SessionController` | Generation-safe retry for soft failures; hard/repeated failure switches to text |
| Mic permission missing | `WakeUpActivity` | `voiceAvailable=false` → text input fallback on screen |
| Schedule failure | `AlarmEditScreen` | Typed failure result; UI never shows the scheduled-success message |
| TTS unavailable | `TtsManager` + `SessionController` | Bounded failure state and typed fallback; local alarm remains audible |
