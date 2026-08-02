package com.anaalarm.ai

import com.anaalarm.data.AppSettings
import com.anaalarm.data.ChatMessage
import com.anaalarm.data.MemoryStore
import com.anaalarm.data.SettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

class ConversationEngineTest {

    private lateinit var client: DeepSeekClient
    private lateinit var memory: MemoryStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var engine: ConversationEngine

    private val now = LocalDateTime.of(2026, 8, 1, 7, 0)

    @Before
    fun setUp() {
        client = mockk()
        memory = mockk(relaxed = true)
        settingsStore = mockk()
        every { settingsStore.settings } returns flowOf(
            AppSettings(apiKey = "key", name = "Alex", language = "en", sessionMinutes = 10)
        )
        coEvery { memory.beginSession() } returns 1001L
        coEvery { memory.yesterdaySummary() } returns "called mom"
        engine = ConversationEngine(client, memory, settingsStore)
    }

    @Test
    fun `startSession greets and persists assistant message`() = runTest {
        val startInput = slot<List<ChatMessageItem>>()
        coEvery { client.respond(any(), capture(startInput)) } returns "Good morning Alex!"

        val reply = engine.startSession(now)

        assertEquals("Good morning Alex!", reply)
        assertTrue(engine.sessionStarted)
        assertEquals("user", startInput.captured.single().role)
        assertTrue(startInput.captured.single().content.isNotBlank())
        coVerify { memory.addMessage(1001L, "assistant", "Good morning Alex!") }
    }

    @Test
    fun `respond sends history plus new user turn`() = runTest {
        coEvery { client.respond(any(), any()) } returns "Hi!"
        engine.startSession(now)

        coEvery { memory.getSessionHistory(1001L, 20) } returns listOf(
            ChatMessage("assistant", "Hi!")
        )
        val inputSlot = slot<List<ChatMessageItem>>()
        coEvery { client.respond(any(), capture(inputSlot)) } returns "Nice!"

        val reply = engine.respond("I slept well")

        assertEquals("Nice!", reply)
        assertEquals(
            listOf(
                ChatMessageItem("assistant", "Hi!"),
                ChatMessageItem("user", "I slept well")
            ),
            inputSlot.captured
        )
        coVerify { memory.addMessage(1001L, "user", "I slept well") }
        coVerify { memory.addMessage(1001L, "assistant", "Nice!") }
    }

    @Test
    fun `wrapUp asks for farewell and endSession saves daily log`() = runTest {
        coEvery { client.respond(any(), any()) } returns "Hi!"
        engine.startSession(now)

        coEvery {
            client.respond(any(), match { it.size == 1 && it[0].role == "user" })
        } returns "Bye!"
        assertEquals("Bye!", engine.wrapUp())

        coEvery { memory.getSessionHistory(1001L, 40) } returns listOf(
            ChatMessage("assistant", "Hi!"),
            ChatMessage("user", "stop"),
            ChatMessage("assistant", "Bye!")
        )

        engine.endSession()

        assertFalse(engine.sessionStarted)
        coVerify {
            memory.saveDailyLog(match { it.contains("assistant: Hi!") && it.contains("Bye!") })
        }
    }

    @Test
    fun `respond before start throws`() = runTest {
        try {
            engine.respond("hello")
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Session not started"))
        }
    }
}
