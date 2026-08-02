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
| `SettingsStore.kt` | DataStore preferences (`anaalarm_settings`); exposes `Flow<AppSettings>` and atomic `update()` |
| `MemoryStore.kt` | The single entry point for app data: alarms CRUD, session/message management, daily logs |

### `ai/` — the brain
| File | Responsibility |
|---|---|
| `DeepSeekClient.kt` | Retrofit client for the DeepSeek Responses API; request/response DTOs; retry logic; `ApiException` |
| `PromptBuilder.kt` | Builds the `instructions` system prompt from profile + date/time + yesterday context |
| `ConversationEngine.kt` | Orchestrates a session: starts it, sends user turns with history, wraps up, persists the daily log |

### `voice/` — spoken conversation
| File | Responsibility |
|---|---|
| `TtsManager.kt` | Wraps Android `TextToSpeech`; utterance-completion callbacks on the main thread; language/pitch/rate control; app-wide singleton |
| `SpeechListener.kt` | Wraps Android `SpeechRecognizer`; callbacks for results/errors; language from settings; `start/stop/destroy` lifecycle |

### `alarm/` — waking you up
| File | Responsibility |
|---|---|
| `AlarmScheduler.kt` | Exact alarm scheduling (`setExactAndAllowWhileIdle`), cancellation, next-trigger computation with repeat days, boot re-scheduling |
| `AlarmReceiver.kt` | Broadcast receiver: posts the full-screen-intent notification and re-schedules the next occurrence |
| `BootReceiver.kt` | Re-schedules all enabled alarms after boot / package replace |
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
The `Application` class constructs every singleton once and exposes them as `lateinit` properties:
`settingsStore`, `memoryStore`, `deepSeekClient`, `conversationEngine`, `ttsManager`, `speechListener`, `alarmScheduler`, plus `applicationScope` (IO dispatcher + SupervisorJob) used by receivers and background work.

## 3. Thread model

- **Compose state** must be mutated on the **main thread**.
- **TTS callbacks** (`onDone`) are posted to the main thread via `Handler(Looper.getMainLooper())`.
- **Speech recognizer callbacks** (`onResults`, `onError`) are posted to the main thread.
- **Network + Room calls** run on `Dispatchers.IO` (Retrofit suspend + Room suspend are safe from any dispatcher; `applicationScope` is IO).
- `SessionController` runs its coroutines on a `MainScope()`; suspend points hop to IO automatically inside Retrofit/Room.
- `SettingsStore` uses DataStore, which is safe on any dispatcher.

There are no explicit synchronization issues by design: each singleton is created once, and per-session state (`SessionController`) is per-activity.

## 4. Wake-up session lifecycle

```
WakeUpActivity.onCreate
        │
        ▼
SessionController.start()
  ├─ read settings (language, session minutes)
  ├─ configure TTS (language, pitch 1.05, rate 1.0)
  ├─ wire SpeechListener callbacks
  └─ beginSession():
        ConversationEngine.startSession()
          ├─ sessionId = now (ms)
          ├─ instructions = PromptBuilder.build(profile, date/time, yesterday)
          └─ POST /responses (no input)  ──► greeting text
      speak(greeting) ──► onDone ──► status=LISTENING ──► startListening()
        │
        ├── user speaks ──► onUserSpeech(text)
        │     ├─ stop phrase? ──► wrapUp()
        │     └─ else ──► ConversationEngine.respond(text)
        │                   ├─ history (last 20) + new message saved
        │                   ├─ POST /responses (full input)
        │                   └─ speak(reply) ──► listen again
        │
        ├── recognizer error ──► silent streak < 2 ? re-listen : speak
        │                        "Sorry, I did not catch that…"
        │
        └── time up (elapsed >= sessionMinutes) ──► wrapUp()
              └─ farewell = POST /responses ("wrap up" prompt)
                 speak(farewell) ──► endSession()
                     ├─ save daily log (last 40 messages, 900 chars)
                     └─ activity finish()
```

**End conditions (all paths):**
1. Stop phrase spoken (see `stopPhrases` in `SessionController`).
2. Session time elapses → AI wraps up with a farewell.
3. Stop button pressed → immediate stop, log saved.
4. Unrecoverable error (API failure) → error message shown, session ends.

## 5. Data model

```
alarms(id PK, hour, minute, days bitmask, snoozeMinutes, enabled)
messages(id PK, sessionId, role "user"|"assistant", content, timestamp)
daily_logs(id PK, date "yyyy-MM-dd", summary)

DataStore keys: api_key, name, language, habits, interests,
                session_minutes, snooze_minutes
```

- `days` bitmask: bit 0 = Sunday … bit 6 = Saturday.
- `sessionId` is a millisecond timestamp — unique enough for a single user.
- Daily log: the concatenation of the last 40 messages of the session (truncated to 900 chars), keyed by today's date. Yesterday's log is injected into tomorrow's prompt.

## 6. Error handling strategy

| Failure | Where | Handling |
|---|---|---|
| No API key | `DeepSeekClient` | Throws `ApiException("no api key")` → localized message on wake-up screen |
| Network failure | `DeepSeekClient` | 1 automatic retry, then `ApiException("network error")` |
| HTTP/API error | `DeepSeekClient` | `ApiException` with server message |
| Empty response | `DeepSeekClient` | `ApiException("empty response")` |
| Speech error / silence | `SpeechListener` + `SessionController` | Re-listen up to 2 times, then the AI asks you to repeat |
| Mic permission missing | `WakeUpActivity` | `voiceAvailable=false` → text input fallback on screen |
| Schedule failure | `AlarmEditScreen` | `runCatching` around `schedule()` (e.g. permission revoked) |
