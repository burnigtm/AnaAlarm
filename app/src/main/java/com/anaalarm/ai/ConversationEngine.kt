package com.anaalarm.ai

import com.anaalarm.data.ChatMessage
import com.anaalarm.data.MemoryStore
import com.anaalarm.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime

class ConversationEngine(
    private val client: DeepSeekClient,
    private val memory: MemoryStore,
    private val settingsStore: SettingsStore
) {
    private val operationMutex = Mutex()

    @Volatile
    private var sessionId: Long = 0
    private var instructions: String = ""
    private var turnCount: Int = 0
    private var sessionStartedAtMillis: Long = 0L

    val sessionStarted: Boolean get() = sessionId != 0L

    suspend fun startSession(now: LocalDateTime = LocalDateTime.now()): String =
        operationMutex.withLock {
        val newSession = !sessionStarted
        if (newSession) {
            sessionId = memory.beginSession()
            sessionStartedAtMillis = System.currentTimeMillis()
            val settings = settingsStore.settings.first()
            val yesterday = memory.yesterdaySummary()
            val streaks = runCatching {
                memory.habitStreaks(settings.habits)
            }.getOrDefault(emptyMap())
            instructions = PromptBuilder.buildInstructions(
                settings = settings,
                now = now,
                yesterday = yesterday,
                habitStreaks = streaks
            )
            turnCount = 0
        }
        val reply = client.respond(
            instructions,
            listOf(ChatMessageItem("user", GREETING_PROMPT))
        )
        if (newSession) {
            // Persist the exact synthetic turn after a successful API response. This keeps
            // subsequent history prefixes cache-stable without retaining a fake conversation
            // when the initial request fails.
            memory.addMessage(sessionId, "user", GREETING_PROMPT)
        }
        memory.addMessage(sessionId, "assistant", reply.text)
        recordUsage(reply)
        reply.text
    }

    /**
     * One non-streaming conversational turn.
     *
     * [insertUserMessage] = false is the controller's post-streaming-failure retry: the user
     * row was already persisted by [respondStreaming] before the stream broke, so neither the
     * database write nor the network input may duplicate it.
     */
    suspend fun respond(
        userText: String,
        insertUserMessage: Boolean = true
    ): String = operationMutex.withLock {
        check(sessionStarted) { "Session not started" }
        val history = memory.getSessionHistory(sessionId, limit = 20)
        if (insertUserMessage) {
            memory.addMessage(sessionId, "user", userText)
        }
        turnCount++
        val input = if (insertUserMessage) {
            history + ChatMessage("user", userText)
        } else {
            // The persisted user turn is already the newest history row.
            check(history.lastOrNull()?.let { it.role == "user" && it.content == userText } == true) {
                "respond(insertUserMessage=false) requires the user turn to be persisted already"
            }
            history
        }
        val reply = client.respond(
            instructions,
            input.map { ChatMessageItem(it.role, it.content) }
        )
        memory.addMessage(sessionId, "assistant", reply.text)
        recordUsage(reply)
        reply.text
    }

    /**
     * Streams one conversational turn, forwarding every visible text delta to [onDelta] in
     * order. The canonical accumulated reply is persisted as the assistant turn only after a
     * completed stream; incomplete/failed streams persist no assistant row (the user row stays
     * for recovery), and [respond] with `insertUserMessage = false` retries without duplicating
     * it when the controller falls back to the non-streaming path.
     */
    suspend fun respondStreaming(
        userText: String,
        onDelta: suspend (String) -> Unit
    ): String = operationMutex.withLock {
        check(sessionStarted) { "Session not started" }
        val history = memory.getSessionHistory(sessionId, limit = 20)
        memory.addMessage(sessionId, "user", userText)
        turnCount++
        val input = history + ChatMessage("user", userText)
        val reply = client.streamRespond(
            instructions,
            input.map { ChatMessageItem(it.role, it.content) },
            onTextDelta = onDelta
        )
        memory.addMessage(sessionId, "assistant", reply.text)
        recordUsage(reply)
        reply.text
    }

    suspend fun wrapUp(): String = operationMutex.withLock {
        check(sessionStarted) { "Session not started" }
        // The farewell must reference the same conversation the user just had, so it is sent
        // with the bounded history exactly like a normal turn instead of a context-free prompt.
        val history = memory.getSessionHistory(sessionId, limit = 20)
        val input = history + ChatMessage("user", PromptBuilder.buildEndPrompt())
        val farewell = client.respond(
            instructions,
            input.map { ChatMessageItem(it.role, it.content) }
        )
        memory.addMessage(sessionId, "assistant", farewell.text)
        recordUsage(farewell)
        farewell.text
    }

    suspend fun endSession() = operationMutex.withLock {
        val endingSessionId = sessionId
        if (endingSessionId == 0L) return@withLock

        // Reset synchronously before the first suspension so Activity teardown/cancellation
        // cannot leave this engine reporting a stale live session.
        sessionId = 0
        instructions = ""
        val finishedTurns = turnCount
        turnCount = 0
        val startedAtMillis = sessionStartedAtMillis
        sessionStartedAtMillis = 0L

        try {
            val history = memory.getSessionHistory(endingSessionId, limit = 40)
            val body = history.joinToString(" | ") { "${it.role}: ${it.content}" }.take(900)
            if (body.isBlank()) {
                // A failed greeting has no durable conversation. In particular, never replace a
                // valid log from an earlier alarm with an empty body.
                memory.clearSession(endingSessionId)
                return@withLock
            }
            // Statistics are recorded before best-effort transcript finalization so a storage
            // failure in the daily log cannot lose the session's shape.
            memory.recordSession(
                startedAtMillis = startedAtMillis,
                endedAtMillis = System.currentTimeMillis(),
                turns = finishedTurns
            )
            memory.appendDailyLog(body)
            // Conversation rows are transient. Delete them only after the durable daily log
            // succeeds, so a storage failure never destroys the sole copy of the session.
            memory.clearSession(endingSessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Session finalization is best-effort; retained rows allow later recovery/debugging.
        }
    }

    /** Stores provider-reported token accounting; calls without a usage report store nothing. */
    private suspend fun recordUsage(reply: ModelReply) {
        val usage = reply.usage ?: return
        memory.recordUsage(
            date = LocalDate.now(),
            sessionId = sessionId,
            inputTokens = usage.inputTokens ?: 0L,
            outputTokens = usage.outputTokens ?: 0L,
            cachedTokens = usage.inputTokenDetails?.cachedTokens ?: 0L,
            totalTokens = usage.totalTokens ?: 0L
        )
    }

    private companion object {
        const val GREETING_PROMPT = "Please begin the wake-up greeting now."
    }
}
