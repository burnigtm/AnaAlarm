package com.anaalarm.ai

import com.anaalarm.data.ChatMessage
import com.anaalarm.data.MemoryStore
import com.anaalarm.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    val sessionStarted: Boolean get() = sessionId != 0L

    suspend fun startSession(now: LocalDateTime = LocalDateTime.now()): String =
        operationMutex.withLock {
        val newSession = !sessionStarted
        if (newSession) {
            sessionId = memory.beginSession()
            val settings = settingsStore.settings.first()
            val yesterday = memory.yesterdaySummary()
            instructions = PromptBuilder.buildInstructions(
                settings = settings,
                now = now,
                yesterday = yesterday
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
        memory.addMessage(sessionId, "assistant", reply)
        reply
    }

    suspend fun respond(userText: String): String = operationMutex.withLock {
        check(sessionStarted) { "Session not started" }
        val history = memory.getSessionHistory(sessionId, limit = 20)
        memory.addMessage(sessionId, "user", userText)
        turnCount++
        val input = history + ChatMessage("user", userText)
        val reply = client.respond(
            instructions,
            input.map { ChatMessageItem(it.role, it.content) }
        )
        memory.addMessage(sessionId, "assistant", reply)
        reply
    }

    suspend fun wrapUp(): String = operationMutex.withLock {
        check(sessionStarted) { "Session not started" }
        val farewell = client.respond(
            instructions,
            listOf(ChatMessageItem("user", PromptBuilder.buildEndPrompt()))
        )
        memory.addMessage(sessionId, "assistant", farewell)
        farewell
    }

    suspend fun endSession() = operationMutex.withLock {
        val endingSessionId = sessionId
        if (endingSessionId == 0L) return@withLock

        // Reset synchronously before the first suspension so Activity teardown/cancellation
        // cannot leave this engine reporting a stale live session.
        sessionId = 0
        instructions = ""
        turnCount = 0

        try {
            val history = memory.getSessionHistory(endingSessionId, limit = 40)
            val body = history.joinToString(" | ") { "${it.role}: ${it.content}" }.take(900)
            if (body.isBlank()) {
                // A failed greeting has no durable conversation. In particular, never replace a
                // valid log from an earlier alarm with an empty body.
                memory.clearSession(endingSessionId)
                return@withLock
            }
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

    private companion object {
        const val GREETING_PROMPT = "Please begin the wake-up greeting now."
    }
}
