# Reliability and latency design

This document records the production invariants introduced by the reliability hardening work.
(Historical tracking on the GitHub CI mirror: issues 2–5. Origin is the source of truth now;
do not treat those GitHub issue URLs as the project home.)

## Alarm delivery invariants

1. A stored schedule uses a private, explicit `ACTION_SCHEDULED_ALARM` PendingIntent. The exported
   manual-fire action exists only in the debug manifest and is rejected by release code.
2. `AlarmScheduler.schedule()` returns `Scheduled`, `Cancelled`, or a typed `Failed` result. UI
   success is shown only for `Scheduled`; exact-alarm denial is never reported as success.
3. `AlarmReceiver.goAsync()` remains open until Room state and the next repeating alarm have been
   committed. One-shot alarms are disabled after delivery; repeating alarms are re-armed.
4. `AlarmService` wakes/notifies/dispatches the UI before ringtone-provider work, starts vibration
   and a generated alarm tone immediately, and upgrades to the configured ringtone through
   asynchronous preparation. This path does not depend on an API key, network, ASR, or TTS.
5. Local alarm audio stops only after TTS reports `onStart`, or after an explicit terminal action
   such as Stop or Snooze. A queued utterance is not treated as audible.
6. Snooze has a distinct PendingIntent and request-code. Snooze delivery does not mutate the
   regular repeat schedule. One-shot cleanup deliberately preserves a snooze created concurrently.
7. Boot, package replacement, wall-clock/time-zone changes, and an exact-alarm permission grant
   all re-run the durable schedule reconciliation.

## Wake-turn latency path

```text
alarm delivered
  ├─ local sound + vibration ─────────────────────────────── immediate audible fallback
  ├─ TTS initialization ────────────────┐
  ├─ speech recognizer preparation      ├─ concurrent cold-start work
  └─ DeepSeek greeting request ─────────┘
                                         ↓
                               TTS onStart → stop local alarm
                                         ↓
                               TTS onDone → 175 ms settle
                                         ↓
                               ASR partials → final transcript
                                         ↓
                               bounded model request → next TTS
```

The implementation minimizes serial work without making the alarm depend on optimization paths:

- TTS readiness and first model generation start concurrently.
- Retrofit/OkHttp, TTS, and recognition are lazy, so killed-process alarm delivery does not build
  the AI/voice stack before `AlarmService` can notify and ring.
- Keystore decryption runs on IO and is cached in memory after the first read; repeated settings
  collections and model turns do not repeatedly decrypt the same API key.
- The recognizer prefers Android's on-device engine on API 31+, then falls back to the default
  recognizer if the engine or selected language is unavailable.
- ASR requests ask for partial results, one best result, and short 700–750 ms endpointing hints.
  Recognizer implementations may ignore these hints.
- The microphone settle delay after TTS is 175 ms. Settle, timeout, and retry jobs are cancelled
  and generation-scoped so an older turn cannot affect a newer turn.
- DeepSeek reasoning is explicitly disabled, spoken output is capped at 96 tokens, and the full
  logical request—including any retry—has a 20-second deadline.
- Only a quick retryable transport failure is retried once. TLS errors, socket timeouts, provider
  errors, and caller cancellation are not retried.
- The synthetic greeting input is persisted, keeping later stateless request prefixes stable for
  provider-side prefix caching.

## Callback and lifecycle safety

- `RecognitionGeneration` invalidates results, errors, and partials from cancelled recognition
  attempts.
- `UtteranceRegistry` binds start/completion/watchdog behavior to one utterance ID. A late callback
  from flushed speech cannot open the microphone or stop a newer alarm path.
- `TtsRequestGeneration` invalidates a background-thread speech request before Stop cleanup is
  posted, preventing previously queued speech from starting after the session closes.
- TTS initialization has an explicit failed state and watchdog. Failure completes queued work and
  switches the session to typed input while local alarm audio remains active.
- `WakeUpSessionViewModel` owns the controller across configuration changes. A different alarm id
  delivered to the `singleTask` activity explicitly retires/replaces the old controller without
  silencing the newer service fallback.
- Every controller owns an isolated `ConversationEngine`; finalization runs once in application
  scope and cannot reset a newer alarm's mutable session state.
- Engine operations are mutex-serialized. Empty/failed greetings never overwrite daily memory;
  non-empty same-day sessions append. Raw rows are deleted only after the daily log succeeds.

## Safe telemetry

`LatencyMetrics` emits parser-stable, metadata-only records under the `AnaLatency` log tag:

```text
event=latency metric=<name> duration_ms=<integer> outcome=<token> key=value ...
```

Durations come from `SystemClock.elapsedRealtimeNanos()`, not wall time. This clock is monotonic,
includes time spent asleep, and is unaffected by a clock or time-zone change. Dimensions are
sorted, token-sanitized, and bounded to 64 characters. The four production measurements are:

| Metric | Start boundary | End boundary | Safe dimensions |
| --- | --- | --- | --- |
| `alarm_to_first_audio` | Immediately after `AlarmReceiver` validates the delivered alarm ID, before direct-boot bookkeeping or service dispatch | The first accepted `ToneGenerator.startTone`, successful `MediaPlayer.start`, or detection that audio for another alarm is already active | `alarm_id`, `audio_path` |
| `asr_start_to_final` | Entry to the logical `startListening` request | The current generation accepts one final result or terminal error, including any on-device-to-default recognizer fallback time | `generation`, `recognizer`, result character count or numeric error code |
| `model_request_to_completion` | Start of one logical provider request, before its first network attempt | A completed response, terminal failure, caller cancellation, or the whole-request deadline, including retry time | process-local request ID, attempts, status or classified error type |
| `tts_request_to_start` | Entry to `speak`, before main-thread dispatch or engine initialization wait | The matching utterance's first `onStart`; if audio never starts, its terminal outcome (`cancelled`, `superseded`, `engine_failed`, `watchdog`, and similar) | process-local request ID and character count |

The audio measurements end at the platform's accepted start callback/call, which is the closest
deterministic app boundary; they do not claim to measure physical speaker output. TTS records are
guarded by utterance ID and ASR records by generation, so late callbacks cannot finish a newer
sample. Model timing spans the complete retry policy rather than reporting each attempt as if it
were a user-visible request.

Other logs may include model attempts, response status, token counts, speech generations, text
lengths, alarm IDs, and typed scheduling results. Prompts, transcripts, response text, provider
response IDs, and API-key characters must never be logged.

## Data and transport boundaries

- Release networking trusts system certificate authorities only. No build disables certificate
  or hostname verification. Debug cleartext is limited to loopback for MockWebServer tests.
- API credentials are AES-GCM encrypted with an Android Keystore key before DataStore writes.
- App backup is disabled; DataStore and Room are also excluded from cloud backup/device transfer.
- Room migrations are explicit through version 5, schemas are exported, message history is
  indexed by `(sessionId, timestamp)`, retention has a timestamp-only index, and raw abandoned
  messages are pruned after seven days. Version 5 adds the `usage` token ledger (pruned after
  90 days) and completed-`session_records` statistics; usage rows store only counts and dates,
  never prompt or transcript content.

## Verification gates

Every change must pass these host-side gates:

```powershell
.\gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin --no-parallel
.\gradlew.bat assembleDebug assembleRelease --no-parallel
.\gradlew.bat lintDebug lintRelease --no-parallel
```

Before a release, also run the on-device suite and manual locked-screen checks described in
[TESTING.md](TESTING.md), including airplane-mode, missing-key, missing-TTS, one-shot, repeating,
snooze, reboot, time-zone change, and exact-alarm-revocation scenarios.

## Responses streaming and phrase-level TTS overlap

**Status: implemented, disabled by default.** The design below is live in
`com.anaalarm.ai.stream` (`SseParser`, `ResponsesStreamDecoder`, `PhraseSegmenter`,
`StreamingTurnCoordinator`) plus `DeepSeekClient.streamRespond`, the ordered-registry QUEUE_ADD
path in `TtsManager`, and `ConversationEngine.respondStreaming`. It is gated by the
`streamingEnabled` setting (`false` by default); when the flag is off, or when a stream fails
before its first audible phrase, the session uses the original bounded non-streaming request.
The provider contract notes that follow remain the reference for that implementation.

The provider contract now makes a safe streaming design possible, but it should be implemented as
a separate, fixture-tested change rather than by changing `stream` to `true` on the current
Retrofit JSON method.

### Provider contract

DeepSeek's [Responses API reference](https://api-docs.deepseek.com/api/create-response/) defines
`POST /responses` streaming as **semantic SSE**. Each frame has an `event:` type and JSON `data:`
whose object contains the same `type` plus an incrementing `sequence_number`. Only
`response.output_text.delta` contributes spoken assistant text. A stream ends with exactly one of
`response.completed`, `response.incomplete`, or `response.failed`; Responses streams explicitly
do **not** send `data: [DONE]`.

This must not be confused with DeepSeek's
[Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/), whose stream is
data-only SSE terminated by `data: [DONE]`. DeepSeek may emit `: keep-alive` SSE comments while a
stream is waiting, as documented under
[Request Keep-Alive](https://api-docs.deepseek.com/quick_start/rate_limit/#request-keep-alive-mechanism).
The Responses parser must ignore comments but must never wait for or accept Chat Completions'
`[DONE]` sentinel.

### Production design

1. **Isolate the transport.** Add a raw `ResponseBody` endpoint dedicated to `stream=true` and
   expose provider-independent `TextDelta`, `Completed`, `Incomplete`, and `Failed` events. The SSE
   decoder dispatches only on blank-line frame boundaries, joins repeated `data:` lines, ignores
   comments, verifies that the `event:` name matches JSON `type`, and rejects duplicate or
   regressing `sequence_number` values. Unknown future event types may be ignored only after their
   valid sequence number has been observed. It also requires a successful `text/event-stream`
   response and caps individual frames and total visible text before allocation can grow without
   limit. Exactly one semantic terminal event is required. A whole-stream deadline and
   response-body `close()` make timeout and cancellation bounded.
2. **Segment only visible output.** Feed only `response.output_text.delta` into a Unicode-aware
   buffer; never feed reasoning, tool, error, or control events to TTS. Emit a phrase at a strong
   sentence boundary after 24 characters, at a clause boundary after 72 characters, or at the last
   whitespace before a 160-character hard limit. These are initial tuning constants, not provider
   semantics. Keep the unfinished tail until more deltas arrive and flush it only on
   `response.completed`. Normalize whitespace without rewriting the accumulated canonical
   response, and never split a Unicode code point.
3. **Give the turn one ordered speech queue.** The first phrase uses `QUEUE_FLUSH`; subsequent
   phrases use `QUEUE_ADD`. Utterance IDs carry both the turn generation and monotonically
   increasing phrase sequence. `UtteranceRegistry` must become an ordered per-turn registry before
   overlap is enabled; a single-active-entry registry cannot safely represent `QUEUE_ADD`.
   Completion callbacks advance only their matching sequence. A bounded channel allows at most
   three outstanding phrases; when full, stream collection suspends and applies network
   backpressure instead of growing the platform TTS queue without limit. Microphone capture opens
   only after both the model's `response.completed` event and the final queued utterance's
   `onDone`.
4. **Make cancellation generation-owned.** Stop, Snooze, activity destruction, or a new turn first
   invalidates the turn generation, then cancels/closes the HTTP stream and calls `tts.stop()`.
   Deltas and callbacks from an older generation are dropped. A terminal incomplete/failed stream
   stops unsaid queued phrases, reports one typed failure, and is never persisted as a completed
   assistant turn. Text that was already spoken cannot be retried invisibly, so automatic
   non-streaming fallback is allowed only before the first phrase starts.
5. **Preserve the current safe path.** Ship overlap behind a provider-capability flag, initially
   off. Unsupported endpoints and failures before first speech use the existing bounded
   non-streaming request. `model_request_to_completion` continues from request dispatch through the
   semantic terminal event; add a separate `model_request_to_first_text` metric when streaming is
   implemented so latency gains are measurable without changing the existing metric's meaning.

### Acceptance tests before enabling overlap

- SSE fixtures cover chunk splits inside UTF-8 and JSON, repeated `data:` lines, blank frames,
  `: keep-alive` comments, unknown forward-compatible events, type mismatches, non-increasing
  sequence numbers, all three terminal event types, EOF without a terminal event, and rejection of
  `[DONE]` on `/responses`.
- Phrase fixtures cover punctuation split across deltas, abbreviations, Unicode, whitespace-only
  deltas, hard-limit splitting, and the final unterminated tail.
- Coordinator tests prove first-phrase `QUEUE_FLUSH`, later `QUEUE_ADD`, exact ordering despite late
  or duplicate TTS callbacks, bounded queue growth, cancellation before/after first speech, no
  persistence on incomplete/failed streams, and no microphone start before both terminal gates.
- A timed MockWebServer integration test proves first TTS `onStart` occurs before
  `response.completed`, while the non-streaming fallback and all existing generation/cancellation
  tests remain green.

This design satisfies issue #3's design gate. Streaming remains intentionally disabled until the
transport, ordered TTS registry, and the acceptance suite land together.

## Direct-boot durability

Enabled schedules are mirrored into device-protected storage with only alarm ID, time, repeat
mask, and snooze duration. `LOCKED_BOOT_COMPLETED` can therefore re-arm alarms without opening
Room, DataStore, credentials, or the AI/voice stack. A one-shot fired before first unlock becomes a
device-protected tombstone: it cannot be re-armed, but its minimal snapshot remains available for
Snooze. After unlock, Room retirement is applied first, the tombstone is removed only after that
succeeds, and the enabled mirror is reconciled from credential-protected state.

Foreground-service and full-screen-intent declarations in Play Console remain release-process
follow-up work rather than a code limitation.
