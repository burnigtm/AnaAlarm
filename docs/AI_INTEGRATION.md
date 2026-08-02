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
- **Streaming:** supported (SSE with `response.output_text.delta` events). AnaAlarm currently uses non-streaming responses for simplicity.
- **Context caching:** automatic; resending history is cheap because the server caches prefix tokens (see DeepSeek's context-caching docs).
- **Reasoning/thinking mode:** not enabled — `temperature` is in effect (set to `0.9` for personality).

## 2. Request model (`DeepSeekClient.kt`)

```kotlin
@Serializable
data class ResponsesRequest(
    val model: String = "deepseek-v4-flash",
    val instructions: String? = null,          // system prompt (persona)
    val input: List<ChatMessageItem>? = null,  // full history + new user turn
    val temperature: Double = 0.9,
    val stream: Boolean = false,
    @SerialName("max_output_tokens") val maxOutputTokens: Int = 400
)
```

`ChatMessageItem` is `{ role, content }` — the same shape as chat completions messages, which the Responses API accepts as `input`.

### Response parsing

The response contains a top-level `output_text` string (the concatenated assistant text). AnaAlarm parses it directly, with a fallback that walks `output[].content[].output_text` parts:

```kotlin
@Serializable
data class ResponsesResponse(
    val id: String? = null,
    @SerialName("output_text") val outputText: String? = null,
    val output: List<OutputItem>? = null,
    val error: ApiErrorBody? = null
)
```

## 3. HTTP client

- **Retrofit 2.11** with the kotlinx-serialization converter (`converter-kotlinx-serialization`).
- OkHttp with 30s connect / 90s read timeouts (LLM generation can take a while).
- Logging interceptor at `BASIC` level (URLs/status only — never logs the body; the API key travels only in the `Authorization` header).
- One automatic retry for `IOException`; `ApiException` re-thrown immediately.

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
2. Over the conversation run 2 or 3 quick mini-quizzes (math, memory of yesterday,
   general knowledge). Give the answer right after.
3. Ask what <name> plans to do today, and remember it.
4. Gently remind about her habits: <habits>.
5. Bring up her interests when it fits: <interests>.
6. Context from a previous day: <yesterday log>. Follow up on promises.
7. Keep the conversation flowing for around <minutes> minutes, building energy.
8. If the user says she is awake / stop / goodbye, answer with a short farewell.
9. If the user says something unclear, steer back to waking up.
10. If asked to wrap up, give a final energetic good-morning send-off in 2-3 sentences.
```

Design decisions baked into the prompt:

- **Spoken output constraints** — short replies, no markdown. TTS can't render markdown, and long replies make the conversation drag.
- **Language lock** — the model must not switch languages; enforced both by the instruction and by always sending the app's language.
- **Time-boxed arc** — the AI knows the session length so it can pace quizzes across the conversation and build energy toward the end.
- **Yesterday context** — the previous day's summary (from `daily_logs`) is injected so the AI can follow up ("Did you water the plants yesterday?").

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

- **Session history:** Room `messages` table, limited to the last 20 messages per request (≈8k tokens — well inside the model's context and good for the automatic context cache).
- **Long-term memory:** after each session, a summary (last 40 messages, 900 chars) is stored in `daily_logs` keyed by date. The next morning's prompt injects the most recent previous-day log.
- **Profile memory:** name, habits, interests, language live in DataStore and are baked into the prompt.

### Why 20 messages?

DeepSeek charges discounted tokens for cached prefixes. Resending history hits the context cache, so longer histories are affordable; 20 keeps responses fast and focused on the current morning.

## 6. Sessions and wrapping up

- `ConversationEngine.startSession()` creates the session (id = current ms), builds the prompt, and fetches the greeting with **empty input**.
- `respond(userText)` appends the user turn and calls the API.
- `wrapUp()` sends a special user message (`PromptBuilder.buildEndPrompt()`) asking for a short farewell, then persists the daily log.
- `endSession()` resets session state and writes the log.

## 7. Failure modes

| Symptom | Cause | User-facing result |
|---|---|---|
| "Set your DeepSeek API key…" | `apiKey` blank in DataStore | `ApiException("no api key")` → localized error |
| "Could not reach the AI…" | Network down / API error | `ApiException` → localized error, session ends |
| Empty/invalid response | Model hiccup | Retried once, then `ApiException("empty response")` |
| 429 / 401 | Rate limit or bad key | Server message passed through in the error |

## 8. Future enhancements

- **Streaming** — consume SSE `response.output_text.delta` events and show/stream text; smaller first-token latency.
- **Web search tool** — the Responses API supports `web_search` server-side; could power "what's the weather today".
- **Farewell memory** — persist a structured summary (planned tasks, promises) instead of raw message concatenation.
