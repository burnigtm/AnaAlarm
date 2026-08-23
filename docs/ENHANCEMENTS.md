# Enhancement program — August 2026

This document records the six-phase enhancement program applied on top of the alarm-delivery
hardening baseline (`9997194`). Every phase landed with its tests and passed the standard
verification gates (`testDebugUnitTest`, `compileDebugAndroidTestKotlin`,
`assembleDebug`/`assembleRelease`, `lintDebug`/`lintRelease`). Unit-test count grew from 130 to
239 across the program.

## Program overview

| Phase | Theme | Highlights |
|---|---|---|
| 1 | Review fixes | Farewell context, pronoun setting, hard session deadline, retry backoff, masked key entry, per-alarm stop scoping |
| 2 | Streaming overlap | Responses SSE transport, phrase segmenter, QUEUE_ADD phrase queue — behind a disabled-by-default flag |
| 3 | Usage & stats foundation | Token ledger + session records (schema v5), cost estimate dashboard |
| 4 | Language, persona, offline voice | Per-app locale (Android 13+), tone/pitch/rate controls, spoken offline farewell |
| 5 | Dismissal control | Stop challenges, reboot-proof snooze caps (DirectBoot v2), custom ringtones (schema v6) |
| 6 | Habit loop & polish | Recap card + streaks (schema v7), stats screen, Material You, next-alarm widget, battery card, encrypted export/import |

## Phase 1 — code-review fixes

| Fix | Where | Behavior change |
|---|---|---|
| A1 | `ConversationEngine.wrapUp()` | The farewell now carries the bounded 20-message history like a normal turn, so it can reference the actual conversation |
| A2 | `Pronouns` + `PromptBuilder` | User-selectable third-person forms (neutral/she/he) replace hardcoded female pronouns in the persona rules |
| A3 | `SessionController` | Hard wall-clock cap (`sessionMinutes + 90 s`) wraps the session up even mid-THINKING |
| A4 | `submitText()` | Returns success; typed drafts survive a rejected submit instead of vanishing |
| A5 | `HomeViewModel` | Upcoming-alarm labels refresh on a 60-second ticker (correct "today/tomorrow" after midnight) |
| A6 | `AlarmService.stop(context, alarmId)` | Queued stops are scoped per alarm so overlapping deliveries cannot silence each other |
| B1 | `DeepSeekClient` | Linear retry backoff (400 ms × attempt) inside the existing deadline; HTTP 429 maps to a typed `rate limited` failure |
| B2 | `SettingsScreen` | The stored API key is never echoed into the field; blank means keep, explicit action removes |
| B3 | `AlarmEditScreen` | Material3 time picker honoring the system 12/24-hour preference replaces the deprecated view dialog |
| B4 | `AnaAlarmApp` | AI-stack access before credential storage fails fast with an actionable message |

## Phase 2 — Responses streaming + phrase-level TTS overlap

Implemented exactly per the contract in [RELIABILITY_AND_LATENCY.md](RELIABILITY_AND_LATENCY.md);
**disabled by default** via the `streamingEnabled` setting.

- `ai/stream/SseParser` — blank-line frames, joined `data:` lines, keep-alive comments ignored,
  CRLF/CR and chunk-boundary safe, frame-size cap, trailing dispatch at EOF.
- `ai/stream/ResponsesStreamDecoder` — strictly increasing `sequence_number`, `event:`↔`type`
  match, `[DONE]` rejected, exactly one terminal event, unknown events ignored only after their
  sequence validates.
- `ai/stream/PhraseSegmenter` — sentence ≥24 / clause ≥72 / hard-limit 160 chars, surrogate-safe
  cuts, decimal-point guard, whitespace-run collapse, tail flush.
- `DeepSeekClient.streamRespond` — raw `@Streaming` endpoint, content-type gate, whole-stream
  deadline, no transport retry, typed incomplete/failed exceptions, body close on cancellation;
  new `model_request_to_first_text` metric while `model_request_to_completion` keeps its meaning.
- `StreamingTurnCoordinator` + `TurnSpeaker` seam — first phrase QUEUE_FLUSH then QUEUE_ADD,
  ≤3 outstanding phrases with network backpressure, settlement requires terminal **and** final
  utterance completion, fallback to non-streaming only before first audio.
- `TtsManager` ordered-registry QUEUE_ADD path with per-turn cancellation; focus/volume held
  across a turn's phrases.
- `ConversationEngine.respondStreaming` persists the assistant row only for completed streams;
  `respond(insertUserMessage = false)` retries without duplicating the user turn.

## Phase 3 — usage ledger and session statistics (schema v5)

- `usage` table: one row per successful model call (date, sessionId, input/output/cached/total
  tokens); indexed by date; pruned after 90 days by an `onOpen` callback. Counts only — never
  prompt or transcript content.
- `session_records`: startedAt/endedAt/durationMs/turns per completed session, written before
  best-effort transcript finalization so stats survive daily-log failures.
- `ModelReply(text, usage)` returned by both request paths; engine stores provider-reported
  accounting per turn.
- Settings → "AI usage": today's tokens/requests, 7-day tokens, estimated cost from documented
  list prices (`UsageCostEstimates`) with cached-input discount; always labeled as an estimate.

## Phase 4 — language, persona, offline voice

- Per-app language via framework `LocaleManager` (Android 13+): `locales_config.xml`, manifest
  attribute, `AppLocales.apply()` on save and `applyIfUnset()` at startup so a system-level
  choice is never overridden. Below 13 the documented device-locale behavior continues. No new
  dependencies — the strict verification lock stays untouched.
- Persona tone (gentle / cheerful / drill-sergeant) injected as a prompt directive; voice pitch
  and speech-rate sliders persisted and applied by `prepareTts`.
- Spoken offline farewell: when the AI fails but TTS works, Ana speaks a localized wrap-up line;
  its audible start silences the alarm (invariant 5) and the session closes through the explicit
  terminal path. Without TTS the previous ring-until-stop behavior is unchanged.

## Phase 5 — dismissal challenges, snooze caps, custom sound (schema v6)

- `DismissalChallenges`: locally generated mental-math (+/−/×, self-consistent answers) and
  4-digit memory codes; whitespace-tolerant checking. Per-alarm `challengeType`; the Stop button
  routes through a challenge dialog (math regenerates on wrong answers; memory shows then hides
  the code). Zero API cost.
- Snooze caps: per-alarm `maxSnoozes` (0 = unlimited) enforced identically before and after
  first unlock via a counter in the device-protected mirror. `DirectBootAlarmStore` format v2
  (counter, cap, ringtone) with v1 upgrade-compat decode; counters survive reboots and unlock
  reconciliation; a fresh regular delivery resets the budget. Typed `SNOOZE_LIMIT_REACHED`
  failure with a dedicated message.
- Custom ringtones: system picker per alarm; URI persisted on the Room row **and** mirrored into
  DirectBoot v2 so it resolves pre-unlock. `AlarmService` resolves custom → default chain off the
  main path, preserving generated-tone-first behavior.

## Phase 6 — habit loop, statistics, polish (schema v7)

- `habit_events` (unique name+date) + pure `StreakCalculator` (today-pending chains through
  yesterday; gaps and explicit unmarks break). Home recap card shows today's durable summary plus
  tappable habit chips with live streak counts; active streaks are celebrated inside the next
  wake-up prompt.
- Stats screen (top-bar entry): sessions this week, average duration, recent sessions, 7-day
  token totals — all from the on-device ledger.
- Material You dynamic color schemes on Android 12+; brand palette remains the fallback.
- Next-alarm widget using framework RemoteViews only (Glance would require new dependencies,
  which the dependency-verification lock discourages). Mirrors `AlarmManager.nextAlarmClock`;
  refreshed on every schedule/cancel.
- Battery-guidance card shown while no battery exemption exists, deep-linking to the
  optimization settings.
- Encrypted export/import via SAF: JSON payload (settings/logs/sessions/habits) encrypted
  AES-GCM under a dedicated Keystore alias (`anaalarm.export.v1`). The payload type structurally
  cannot carry the API key. Restores merge by natural keys.

## Schema migration ledger

| Version | Adds |
|---|---|
| 5 | `usage` (+date index, 90-day retention), `session_records` |
| 6 | `alarms.challengeType`, `alarms.maxSnoozes`, `alarms.ringtoneUri` (safe defaults) |
| 7 | `habit_events` (+unique name/date index) |

All migrations are covered by the JVM chain test (`migration one to seven …`) walking a real
version-1 database file, and instrumented MigrationTestHelper validation remains green.

## Test additions

New suites: `SseParserTest`, `ResponsesStreamDecoderTest`, `PhraseSegmenterTest`,
`OrderedUtteranceRegistryTest`, `StreamingTurnCoordinatorTest`, `DeepSeekClientStreamTest`,
`DebugTlsTest`, `PronounsTest`, `DismissalChallengesTest`, `StreakCalculatorTest`,
`DataExportTest`, `AppLocalesTest`. Extended: client/engine/store/migration/scheduler/
prompt/latency/home suites. Environment note: unit-test JVM args now include
`-Djdk.attach.allowAttachSelf=true` so MockK works on JDK 21 hosts (CI's Temurin 17 unaffected).

## CI follow-up fixes (instrumented-suite regressions caught by GitHub Actions)

Two interactions between program changes surfaced in the on-device suite
(`AlarmFiringInstrumentedTest.snoozeStillWorksAfterAiFailureAndStopsTheFallback`, API 26 + 36):

1. **Offline farewell only after audible speech; session stays open.** Phase 4's spoken farewell
   originally fired on every AI failure — including a cold-start missing-key failure, where it
   silenced the alarm before any user action and auto-finished the activity (dropping a Snooze
   pressed around it). Final design: the farewell is spoken only when TTS already produced audio
   this session (`everSpoken` gate); its audible start still silences the alarm per invariant 5,
   completion only marks the conversation ENDED, and Stop/Snooze remain available. Cold-start
   failures keep the original ring-until-explicit-action behavior. Related: Phase 5's
   `requestStop()` initially guarded on `ended`, which bricked the Stop button on exactly those
   ended-but-open screens; the explicit stop path now bypasses that guard.
2. **Queued service stops are exact-id scoped, time-boxed, and foreground-safe.** The per-alarm
   stop scoping initially kept an unscoped `[-1]` stop request alive indefinitely, and honoring
   any queued stop skipped `startForeground()` entirely — crashing under the
   `startForegroundService()` contract. Final design: the service always promotes to foreground
   first; `[-1]` stops resolve against the service's last-started alarm id, every queued stop
   matches exactly one alarm, and honors are gated to 15 seconds.
3. **Locale application is change-gated.** Phase 4 applied the stored language on every process
   start and after every settings save, forcing activity recreations even when nothing changed;
   a recreation racing the Compose test recomposer crashed with
   `CalledFromWrongThreadException`, and the switch ran before the save confirmation could show.
   Final design: `apply()` no-ops when the requested language already matches the effective
   configuration, startup sync was removed entirely, and Settings applies the locale only after
   the confirmation snackbar.
4. **Device tests scroll to below-fold editor controls.** The Phase-6 sections made
   `AlarmEditScreen` taller than small viewports; clicks on clipped nodes silently missed. The
   affected suites now `performScrollTo()` before interacting, matching real user behavior, and
   the instrumented `wrapUp` contract was aligned with the Phase-1 bounded-history change.

## Follow-up: wake-up buddy

A procedurally drawn animal companion (Kiko the cheetah, Dax the dino, Zuri the zebra) sits
beside Ana on the wake-up screen, inside the stop-challenge dialog, on the home recap card, and
in Settings as a live picker. The species is stored in DataStore (`avatar`), sanitised with
`Avatars.from` on read, and mentioned in the system prompt at most once per session. Mood follows
session status (sleepy → talking → listening → thinking → happy); API errors and wrong challenge
answers force a sad pose. Encrypted export includes the field; legacy payloads omit it and must
not clobber a buddy the user already picked.

New unit suites: `AvatarsTest`, `AvatarPoseTest`, `SettingsAvatarTest`, `AvatarMoodMappingTest`,
plus buddy cases in `PromptBuilderTest`, `DataExportTest`, and `HomeViewModelTest`. New device
suites: `SettingsBuddyInstrumentedTest` (4), `HomeRecapBuddyInstrumentedTest` (2),
`WakeUpBuddyInstrumentedTest` (6), `BuddyAvatarRenderInstrumentedTest` (2), and two extra
`SettingsStoreInstrumentedTest` cases. Aggregate device lock: 173 → 189.

## Deferred intentionally

- "Talk to Ana anytime" free-chat entry point (largest UI surface, least alarm value).
- Gradual volume ramp on custom ringtones (generated-tone-first path already covers audibility).
- Glance-based widget (dependency-lock trade-off; framework RemoteViews shipped instead).
