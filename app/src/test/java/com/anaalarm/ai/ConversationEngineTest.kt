package com.anaalarm.ai

import com.anaalarm.data.AppSettings
import com.anaalarm.data.ChatMessage
import com.anaalarm.data.MemoryStore
import com.anaalarm.data.SettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
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

    private fun reply(text: String, usage: ResponseUsage? = null) = ModelReply(text, usage)

    @Test
    fun `startSession greets and persists assistant message`() = runTest {
        val startInput = slot<List<ChatMessageItem>>()
        coEvery { client.respond(any(), capture(startInput)) } returns reply("Good morning Alex!")

        val reply = engine.startSession(now)

        assertEquals("Good morning Alex!", reply)
        assertTrue(engine.sessionStarted)
        assertEquals("user", startInput.captured.single().role)
        assertTrue(startInput.captured.single().content.isNotBlank())
        coVerify {
            memory.addMessage(1001L, "user", "Please begin the wake-up greeting now.")
        }
        coVerify { memory.addMessage(1001L, "assistant", "Good morning Alex!") }
    }

    @Test
    fun `respond sends history plus new user turn`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)

        coEvery { memory.getSessionHistory(1001L, 20) } returns listOf(
            ChatMessage("user", "Please begin the wake-up greeting now."),
            ChatMessage("assistant", "Hi!")
        )
        val inputSlot = slot<List<ChatMessageItem>>()
        coEvery { client.respond(any(), capture(inputSlot)) } returns reply("Nice!")

        val reply = engine.respond("I slept well")

        assertEquals("Nice!", reply)
        assertEquals(
            listOf(
                ChatMessageItem("user", "Please begin the wake-up greeting now."),
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
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)

        coEvery {
            client.respond(any(), match { it.size == 1 && it[0].role == "user" })
        } returns reply("Bye!")
        assertEquals("Bye!", engine.wrapUp())

        coEvery { memory.getSessionHistory(1001L, 40) } returns listOf(
            ChatMessage("assistant", "Hi!"),
            ChatMessage("user", "stop"),
            ChatMessage("assistant", "Bye!")
        )

        engine.endSession()

        assertFalse(engine.sessionStarted)
        coVerifyOrder {
            memory.appendDailyLog(match { it.contains("assistant: Hi!") && it.contains("Bye!") })
            memory.clearSession(1001L)
        }
    }

    @Test
    fun `wrapUp sends bounded history plus the end prompt`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)

        coEvery { memory.getSessionHistory(1001L, 20) } returns listOf(
            ChatMessage("user", "Please begin the wake-up greeting now."),
            ChatMessage("assistant", "Hi!"),
            ChatMessage("user", "I will call mom today")
        )
        val inputSlot = slot<List<ChatMessageItem>>()
        coEvery { client.respond(any(), capture(inputSlot)) } returns reply("Bye!")

        assertEquals("Bye!", engine.wrapUp())

        // The farewell must reference the same conversation the user just had.
        assertEquals(
            listOf(
                ChatMessageItem("user", "Please begin the wake-up greeting now."),
                ChatMessageItem("assistant", "Hi!"),
                ChatMessageItem("user", "I will call mom today"),
                ChatMessageItem("user", PromptBuilder.buildEndPrompt())
            ),
            inputSlot.captured
        )
        coVerify { memory.addMessage(1001L, "assistant", "Bye!") }
    }

    @Test
    fun `respondStreaming persists the assistant turn and forwards deltas in order`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)

        coEvery { memory.getSessionHistory(1001L, 20) } returns listOf(
            ChatMessage("user", "Please begin the wake-up greeting now."),
            ChatMessage("assistant", "Hi!")
        )
        val inputSlot = slot<List<ChatMessageItem>>()
        coEvery { client.streamRespond(any(), capture(inputSlot), any(), any()) } answers {
            val onDelta = thirdArg<suspend (String) -> Unit>()
            kotlinx.coroutines.runBlocking {
                onDelta("Good ")
                onDelta("morning!")
            }
            reply("Good morning!")
        }

        val seen = mutableListOf<String>()
        val reply = engine.respondStreaming("I am barely awake") { seen += it }

        assertEquals("Good morning!", reply)
        assertEquals(listOf("Good ", "morning!"), seen)
        coVerify { memory.addMessage(1001L, "user", "I am barely awake") }
        coVerify { memory.addMessage(1001L, "assistant", "Good morning!") }
        // The streamed request carries the same bounded history contract as a normal turn.
        assertEquals(
            listOf(
                ChatMessageItem("user", "Please begin the wake-up greeting now."),
                ChatMessageItem("assistant", "Hi!"),
                ChatMessageItem("user", "I am barely awake")
            ),
            inputSlot.captured
        )
    }

    @Test
    fun `failed stream persists no assistant row but keeps the user turn`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)

        coEvery { memory.getSessionHistory(1001L, 20) } returns emptyList()
        coEvery {
            client.streamRespond(any(), any(), any(), any())
        } throws StreamIncompleteException("max_output_tokens")

        try {
            engine.respondStreaming("hello") { }
            throw AssertionError("expected StreamIncompleteException")
        } catch (_: StreamIncompleteException) {
            // Expected.
        }

        // The user row stays for recovery; no assistant row may fake a completed turn.
        // (The single assistant call is the session greeting, not the streamed turn.)
        coVerify { memory.addMessage(1001L, "user", "hello") }
        coVerify(exactly = 1) { memory.addMessage(1001L, "assistant", "Hi!") }
    }

    @Test
    fun `fallback respond can skip re-inserting the user turn`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)

        // History already ends with the user turn persisted by the failed streaming attempt.
        coEvery { memory.getSessionHistory(1001L, 20) } returns listOf(
            ChatMessage("user", "hello")
        )
        val inputSlot = slot<List<ChatMessageItem>>()
        coEvery { client.respond(any(), capture(inputSlot)) } returns reply("Recovered")

        engine.respond("hello", insertUserMessage = false)

        // Exactly one user turn reaches the network, and nothing is re-persisted.
        assertEquals(
            listOf(ChatMessageItem("user", "hello")),
            inputSlot.captured
        )
        coVerify(exactly = 0) { memory.addMessage(1001L, "user", "hello") }
        coVerify { memory.addMessage(1001L, "assistant", "Recovered") }
    }

    @Test
    fun `fallback flag without a persisted user turn fails fast`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)
        coEvery { memory.getSessionHistory(1001L, 20) } returns emptyList()

        try {
            engine.respond("hello", insertUserMessage = false)
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("persisted"))
        }
    }

    @Test
    fun `respond persists provider-reported token usage`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)

        coEvery { memory.getSessionHistory(1001L, 20) } returns emptyList()
        val inputSlot = slot<List<ChatMessageItem>>()
        coEvery { client.respond(any(), capture(inputSlot)) } returns ModelReply(
            "Nice!",
            ResponseUsage(
                inputTokens = 120,
                outputTokens = 18,
                totalTokens = 138,
                inputTokenDetails = InputTokenDetails(cachedTokens = 90)
            )
        )

        engine.respond("I slept well")

        coVerify {
            memory.recordUsage(
                date = any(),
                sessionId = 1001L,
                inputTokens = 120,
                outputTokens = 18,
                cachedTokens = 90,
                totalTokens = 138
            )
        }
    }

    @Test
    fun `turns without a provider usage report store nothing`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)
        coEvery { memory.getSessionHistory(1001L, 20) } returns emptyList()

        engine.respond("hello")

        coVerify(exactly = 0) {
            memory.recordUsage(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `endSession records the completed session shape for statistics`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)

        // One real conversational turn so the session has a nonzero turn count.
        coEvery { memory.getSessionHistory(1001L, 20) } returns listOf(
            ChatMessage("user", "Please begin the wake-up greeting now."),
            ChatMessage("assistant", "Hi!")
        )
        val inputSlot = slot<List<ChatMessageItem>>()
        coEvery { client.respond(any(), capture(inputSlot)) } returns reply("Nice!")
        engine.respond("I slept well")

        coEvery { memory.getSessionHistory(1001L, 40) } returns listOf(
            ChatMessage("assistant", "Hi!"),
            ChatMessage("user", "I slept well")
        )

        engine.endSession()

        val startedAt = slot<Long>()
        val endedAt = slot<Long>()
        coVerify {
            memory.recordSession(capture(startedAt), capture(endedAt), 1)
        }
        assertTrue(endedAt.captured >= startedAt.captured)
        assertTrue(startedAt.captured > 0)
    }

    @Test
    fun `failed greeting sessions write no statistics record`() = runTest {
        coEvery { client.respond(any(), any()) } throws ApiException("network error")
        coEvery { memory.getSessionHistory(1001L, 40) } returns emptyList()

        try {
            engine.startSession(now)
            throw AssertionError("expected ApiException")
        } catch (_: ApiException) {
            // Expected.
        }
        engine.endSession()

        coVerify(exactly = 0) { memory.recordSession(any(), any(), any()) }
    }

    @Test
    fun `endSession retains message rows when daily log persistence fails`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)
        coEvery { memory.getSessionHistory(1001L, 40) } returns listOf(
            ChatMessage("assistant", "Hi!")
        )
        coEvery { memory.appendDailyLog(any()) } throws IllegalStateException("disk full")

        engine.endSession()

        assertFalse(engine.sessionStarted)
        coVerify(exactly = 0) { memory.clearSession(any()) }
    }

    @Test
    fun `failed greeting finalization never writes an empty daily log`() = runTest {
        coEvery { client.respond(any(), any()) } throws ApiException("network error")
        coEvery { memory.getSessionHistory(1001L, 40) } returns emptyList()

        try {
            engine.startSession(now)
            throw AssertionError("expected ApiException")
        } catch (_: ApiException) {
            // Expected.
        }
        engine.endSession()

        assertFalse(engine.sessionStarted)
        coVerify(exactly = 0) { memory.appendDailyLog(any()) }
        coVerify { memory.clearSession(1001L) }
    }

    @Test
    fun `endSession resets state but preserves cancellation`() = runTest {
        coEvery { client.respond(any(), any()) } returns reply("Hi!")
        engine.startSession(now)
        coEvery { memory.getSessionHistory(1001L, 40) } throws CancellationException("stopped")

        try {
            engine.endSession()
            throw AssertionError("expected CancellationException")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertFalse(engine.sessionStarted)
        coVerify(exactly = 0) { memory.appendDailyLog(any()) }
        coVerify(exactly = 0) { memory.clearSession(any()) }
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

