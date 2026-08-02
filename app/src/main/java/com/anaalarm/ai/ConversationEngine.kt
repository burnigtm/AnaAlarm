package com.anaalarm.ai

import com.anaalarm.data.ChatMessage
import com.anaalarm.data.MemoryStore
import com.anaalarm.data.SettingsStore
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

class ConversationEngine(
    private val client: DeepSeekClient,
    private val memory: MemoryStore,
    private val settingsStore: SettingsStore
) {
    private var sessionId: Long = 0
    private var instructions: String = ""
    private var turnCount: Int = 0

    val sessionStarted: Boolean get() = sessionId != 0L

    suspend fun startSession(now: LocalDateTime = LocalDateTime.now()): String {
        if (!sessionStarted) {
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
        // Responses API accepts instructions-only, but always providing an input turn
        // matches DeepSeek examples and avoids empty-input edge cases.
        val reply = client.respond(
            instructions,
            listOf(ChatMessageItem("user", "Please begin the wake-up greeting now."))
        )
        memory.addMessage(sessionId, "assistant", reply)
        return reply
    }

    suspend fun respond(userText: String): String {
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
        return reply
    }

    suspend fun wrapUp(): String {
        check(sessionStarted) { "Session not started" }
        val farewell = client.respond(
            instructions,
            listOf(ChatMessageItem("user", PromptBuilder.buildEndPrompt()))
        )
        memory.addMessage(sessionId, "assistant", farewell)
        return farewell
    }

    suspend fun endSession() {
        if (!sessionStarted) return
        runCatching {
            val history = memory.getSessionHistory(sessionId, limit = 40)
            val body = history.joinToString(" | ") { "${it.role}: ${it.content}" }.take(900)
            memory.saveDailyLog(body)
        }
        sessionId = 0
        instructions = ""
        turnCount = 0
    }
}
