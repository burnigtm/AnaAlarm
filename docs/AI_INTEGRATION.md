# AI Integration — DeepSeek Responses API

AnaAlarm talks to DeepSeek's **Responses API** using the `deepseek-v4-flash` model. This document covers the API contract, the request/response model in code, prompt design, and the memory strategy.

## 1. API contract (verified against DeepSeek docs)

| Property | Value |
|---|---|
| Base URL | `https://api.deepseek.com` |
| Endpoint | `POST /responses` (OpenAI Responses API format) |
| Model | `deepseek-v4-flash` (the only model currently supported by the Responses API) |
| Auth | `Authorization: Bearer <api key>` |
| Key source | [platform.deepseek.com](https://platform.deepseek.com/api_keys) |

Compatibility notes that shape this app's design:

- **Stateless:** `previous_response_id`, `conversation`, and `store` are **not supported**. The client must resend the full message history on every call.
- **`instructions`:** supported; inserted as the first system message. Used for the persona prompt.
- **`input`:** string or list of input items; roles `user`/`assistant`/`system` supported. Image/file inputs are not.
- **Streaming:** the provider supports semantic SSE frames. Text arrives in
  `response.output_text.delta` events, but completion is declared by a terminal
  `response.completed`, `response.incomplete`, or `response.failed` event—not by Chat
  Completions' `data: [DONE]` sentinel. AnaAlarm deliberately remains non-streaming until the
  ordered phrase/cancellation design is implemented as a complete slice.
- **Context caching:** automatic; resending history is cheap because the server caches prefix tokens (see DeepSeek's context-caching docs).
- **Reasoning/thinking mode:** explicitly disabled with `reasoning.effort = "none"`.
  DeepSeek enables thinking when this field is omitted; disabling it avoids paying first-token
  latency and the output-token budget for reasoning that is unnecessary for short wake-up chat.

## 2. Request model (`DeepSeekClient.kt`)

```kotlin
@Serializable
data class ResponsesRequest(
    val model: String = "deepseek-v4-flash",
    val instructions: String? = null,          // system prompt (persona)
    val input: List<ChatMessageItem>? = null,  // full history + new user turn
    val temperature: Double = 0.9,
    val reasoning: ReasoningOptions = ReasoningOptions(effort = "none"),
    val stream: Boolean = false,
    @SerialName("max_output_tokens") val maxOutputTokens: Int = 96
)
```

`ChatMessageItem` is `{ role, content }` — the same shape as chat completions messages, which the Responses API accepts as `input`.

### Response parsing

The client accepts a compatibility top-level `output_text` string when present, then falls back
to the canonical nested `output[].content[]` output-text parts:

```kotlin
@Serializable
data class ResponsesResponse(
    val id: String? = null,
    val status: String? = null,
    @SerialName("output_text") val outputText: String? = null,
    val output: List<OutputItem>? = null,
    @SerialName("incomplete_details") val incompleteDetails: IncompleteDetails? = null,
    val usage: ResponseUsage? = null,
    val error: ApiErrorBody? = null
)
```

Only `completed` responses (or legacy responses with no status) are accepted. `incomplete`
responses surface their structured reason, and `failed` responses surface the API error. A
partial `output_text` from an incomplete response is never spoken as if it were complete.

`usage` parses input, output, total, cached-input, and reasoning token counts. These counts,
status, attempt count, and elapsed time are logged for latency diagnosis. Prompts, transcripts,
response text, response IDs, and API-key characters are never logged.

## 3. HTTP client

- **Retrofit 2.11** with the kotlinx-serialization converter (`converter-kotlinx-serialization`).
- A 20-second coroutine deadline covers the complete logical operation, including its retry.
- OkHttp also enforces a 20-second whole-call timeout, with 10-second connect/write and
  20-second read limits.
- Logging interceptor at `BASIC` level (URLs/status only — never logs the body; the API key travels only in the `Authorization` header).
- One immediate retry for a quick, retryable `IOException`. TLS failures and elapsed socket
  timeouts are not retried. Caller cancellation is always rethrown and never converted into an
  API error or retried.
- Both debug and release clients use the platform certificate trust store and hostname verifier.
  There is no trust-all development mode.

## 4. Prompt design (`PromptBuilder.kt`)

The `instructions` system prompt is generated per session:

```
You are Ana, the warm, cheerful and energetic morning companion of <name>.
Everything you say is spoken out loud by a text-to-speech engine, so:
- Keep every reply to 1 or 2 short sentences. Never use markdown, lists, symbols or emojis.
- Use plain, natural spoken text.
- Always reply in <language>. Never switch languages.

Today is <date> and it is <time>.

This is the morning wake-up session of <minutes> minutes. Rules:
1. On the very first message, greet <name> warmly and ask how the night was.
2. Over the conversation run 2 or 3 quick mini-quizzes (math, memory of a prior day,
   general knowledge). Give the answer right after.
3. Ask what <name> plans to do today, and remember it.
4. Gently remind about her habits: <habits>.
5. Bring up her interests when it fits: <interests>.
6. Context from a previous day: <most recent prior-day log>. Follow up on promises.
7. Keep the conversation flowing for around <minutes> minutes, building energy.
8. If the user says she is awake / stop / goodbye, answer with a short farewell.
9. If the user says something unclear, steer back to waking up.
10. If asked to wrap up, give a final energetic good-morning send-off in 2-3 sentences.
```

Design decisions baked into the prompt:

- **Spoken output constraints** — short replies, no markdown. TTS can't render markdown, and long replies make the conversation drag.
- **Language lock** — the model must not switch languages; enforced both by the instruction and by always sending the app's language.
- **Time-boxed arc** — the AI knows the session length so it can pace quizzes across the conversation and build energy toward the end.
- **Prior-day context** — the most recent non-empty log before today (from `daily_logs`) is
  injected, even when the last completed session was more than one day ago.

## 5. Memory strategy

Because the API is stateless, all memory lives on-device:

```
Conversation turn
     │
     ▼
MemoryStore.addMessage(sessionId, "user", text)     // persisted immediately
history = MemoryStore.getSessionHistory(sessionId, limit = 20)  // last 20 msgs
input = history + new user message
POST /responses(instructions, input)
reply ──► MemoryStore.addMessage(sessionId, "assistant", reply)
```

- **Session history:** Room `messages` table, limited to the last 20 messages per request. The
  synthetic greeting request is persisted too, so later requests reproduce the exact original
  prefix and remain eligible for automatic prefix caching.
- **Long-term memory:** after each non-empty session, up to 900 characters of role-labelled raw
  history are appended to a bounded `daily_logs` row keyed by date. A failed greeting cannot
  blank-replace an earlier session. The next prompt injects the most recent previous-day log.
- **Profile memory:** name, habits, interests, language live in DataStore and are baked into the prompt.
- **Retention:** after the daily log is successfully saved, transient message rows for that
  session are deleted. If log persistence fails or is cancelled, rows are retained so the only
  copy of the conversation is not destroyed.

### Why 20 messages?

DeepSeek charges discounted tokens for cached prefixes. Resending history hits the context cache, so longer histories are affordable; 20 keeps responses fast and focused on the current morning.

## 6. Sessions and wrapping up

- `ConversationEngine.startSession()` creates the session (id = current ms), builds the prompt,
  persists a synthetic user greeting request, and sends that request to fetch the greeting.
- `respond(userText)` appends the user turn and calls the API.
- `wrapUp()` sends a special user message (`PromptBuilder.buildEndPrompt()`) asking for a short
  farewell.
- `endSession()` resets session state, appends non-empty history to the daily log, and only then
  removes transient message rows.

## 7. Failure modes

| Symptom | Cause | Client exception |
|---|---|---|
| "Set your DeepSeek API key…" | Decrypted `SettingsStore` credential is blank | `ApiException("no api key")` |
| "Could not reach the AI…" | Network down / API error | Stable mapped `ApiException` |
| Logical request exceeds 20 seconds | Slow network/model | Call is cancelled; `ApiException("network error")` |
| Empty/invalid response | Model hiccup | `ApiException("empty response")`, without retry |
| Incomplete response | Output cap or provider interruption | Structured reason is surfaced; partial text is discarded |
| Failed response | Provider reports `failed`/`error` | Provider message, or stable `response failed` fallback |
| 429 / 5xx | Rate limit or server error | `ApiException("HTTP <status>")`, without retry |
| 401 / 403 | Bad or revoked key | `ApiException("invalid api key")` |

`SessionController` maps these stable client exceptions to localized recovery messages. Provider
status text is not shown raw to the user.

## 8. Future enhancements

- **Safe streaming speech** — implement the frame parser, sequence validation, Unicode phrase
  segmenter, generation-scoped TTS queue, cancellation, fixtures, and feature-flag rollout
  specified in [`RELIABILITY_AND_LATENCY.md`](RELIABILITY_AND_LATENCY.md#responses-streaming-and-phrase-level-tts-overlap).
- **Web search tool** — the Responses API supports `web_search` server-side; could power "what's the weather today".
- **Farewell memory** — persist a structured summary (planned tasks, promises) instead of raw message concatenation.
