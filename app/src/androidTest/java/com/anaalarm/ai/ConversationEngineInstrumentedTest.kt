package com.anaalarm.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.anaalarm.support.FakeAiServer
import com.anaalarm.support.TestEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/** The conversation state machine, wired to the real Room memory and a fake DeepSeek. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ConversationEngineInstrumentedTest {

    private lateinit var server: FakeAiServer
    private lateinit var engine: ConversationEngine

    @Before
    fun setUp() = runBlocking {
        TestEnv.clearDatabase()
        TestEnv.resetSettings()
        TestEnv.app.settingsStore.update(
            apiKey = "sk-engine",
            name = "Marina",
            language = "en",
            habits = listOf("water the plants"),
            interests = listOf("photography"),
            sessionMinutes = 12
        )
        server = FakeAiServer()
        server.start()
        engine = ConversationEngine(server.client(), TestEnv.app.memoryStore, TestEnv.app.settingsStore)
    }

    @After
    fun tearDown() {
        server.shutdown()
        TestEnv.clearDatabase()
        TestEnv.resetSettings()
    }

    private fun lastRequestBody(): JSONObject =
        JSONObject(server.takeRequest()!!.body.readUtf8())

    @Test
    fun startSessionReturnsTheGreetingAndMarksTheSessionOpen() = runBlocking {
        assertFalse(engine.sessionStarted)
        server.enqueueReply("Good morning Marina! How did you sleep?")

        val greeting = engine.startSession()

        assertEquals("Good morning Marina! How did you sleep?", greeting)
        assertTrue(engine.sessionStarted)
    }

    @Test
    fun instructionsCarryThePersonaSettingsAndYesterdaysContext() = runBlocking {
        TestEnv.app.memoryStore.saveDailyLog(
            "user: I will call my mom",
            LocalDate.now().minusDays(1)
        )
        server.enqueueReply("hello")

        engine.startSession(now = LocalDateTime.of(2026, 3, 10, 6, 45))

        val instructions = lastRequestBody().getString("instructions")
        assertTrue(instructions.contains("You are Ana"))
        assertTrue(instructions.contains("Marina"))
        assertTrue(instructions.contains("Always reply in English"))
        assertTrue(instructions.contains("water the plants"))
        assertTrue(instructions.contains("photography"))
        assertTrue(instructions.contains("call my mom"))
        assertTrue(instructions.contains("12 minutes"))
        assertTrue(instructions.contains("Tuesday, March 10, 2026"))
    }

    @Test
    fun portugueseSettingsSwitchTheReplyLanguageDirective() = runBlocking {
        TestEnv.app.settingsStore.update(language = "pt")
        server.enqueueReply("Bom dia!")

        engine.startSession()

        assertTrue(
            lastRequestBody().getString("instructions")
                .contains("Always reply in Portuguese (Brazil)")
        )
    }

    @Test
    fun respondBeforeStartSessionIsRejected() = runBlocking {
        try {
            engine.respond("hello")
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Session not started", e.message)
        }
    }

    @Test
    fun respondSendsThePriorHistoryWithTheNewUserTurn() = runBlocking {
        server.enqueueReply("Good morning!")
        engine.startSession()
        server.takeRequest()

        server.enqueueReply("Glad to hear it.")
        val reply = engine.respond("I slept really well")

        assertEquals("Glad to hear it.", reply)

        val turns = lastRequestBody().getJSONArray("input")
        assertEquals(3, turns.length())
        assertEquals("user", turns.getJSONObject(0).getString("role"))
        assertEquals(
            "Please begin the wake-up greeting now.",
            turns.getJSONObject(0).getString("content")
        )
        assertEquals("assistant", turns.getJSONObject(1).getString("role"))
        assertEquals("Good morning!", turns.getJSONObject(1).getString("content"))
        assertEquals("user", turns.getJSONObject(2).getString("role"))
        assertEquals("I slept really well", turns.getJSONObject(2).getString("content"))
    }

    @Test
    fun historyGrowsAcrossTurns() = runBlocking {
        server.respondAlwaysWith("noted")
        engine.startSession()
        engine.respond("first")
        engine.respond("second")

        repeat(2) { server.takeRequest() }
        val turns = JSONObject(server.takeRequest()!!.body.readUtf8()).getJSONArray("input")

        // synthetic greeting prompt + greeting + first user turn + first reply + second user turn
        assertEquals(5, turns.length())
        assertEquals("second", turns.getJSONObject(4).getString("content"))
    }

    @Test
    fun wrapUpAsksForTheFarewell() = runBlocking {
        server.enqueueReply("Good morning!")
        engine.startSession()
        server.takeRequest()

        server.enqueueReply("Time to get up, have a great day!")
        val farewell = engine.wrapUp()

        assertEquals("Time to get up, have a great day!", farewell)
        // Phase-1 contract: the farewell carries the bounded history, so the end prompt is the
        // final turn after the synthetic greeting and the assistant reply.
        val turns = lastRequestBody().getJSONArray("input")
        assertEquals(3, turns.length())
        assertEquals(
            "Please begin the wake-up greeting now.",
            turns.getJSONObject(0).getString("content")
        )
        assertEquals(
            PromptBuilder.buildEndPrompt(),
            turns.getJSONObject(2).getString("content")
        )
    }

    @Test
    fun endSessionStoresTheDayLogAndClearsState() = runBlocking {
        server.respondAlwaysWith("sure")
        engine.startSession()
        engine.respond("I will water the plants today")

        engine.endSession()

        val summary = TestEnv.app.memoryStore.todaySummary()
        assertTrue(summary.contains("assistant: sure"))
        assertTrue(summary.contains("user: I will water the plants today"))
        assertFalse(engine.sessionStarted)
    }

    @Test
    fun endSessionWithoutASessionIsANoOp() = runBlocking {
        engine.endSession()
        assertEquals("", TestEnv.app.memoryStore.todaySummary())
        assertFalse(engine.sessionStarted)
    }

    @Test
    fun startSessionIsIdempotentWhileASessionIsOpen() = runBlocking {
        server.respondAlwaysWith("hi")
        engine.startSession(now = LocalDateTime.of(2026, 3, 10, 6, 0))
        val first = JSONObject(server.takeRequest()!!.body.readUtf8()).getString("instructions")

        engine.startSession(now = LocalDateTime.of(2026, 3, 10, 23, 0))
        val second = JSONObject(server.takeRequest()!!.body.readUtf8()).getString("instructions")

        assertEquals("re-entering a live session must not rebuild the prompt", first, second)
    }

    @Test
    fun aFailingBackendPropagatesTheMappedError() = runBlocking {
        server.enqueueHttpError(401)
        try {
            engine.startSession()
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("invalid api key", e.message)
        }
    }

    @Test
    fun failedGreetingDoesNotEraseAnEarlierDailyLog() = runBlocking {
        TestEnv.app.memoryStore.saveDailyLog("earlier successful session")
        server.enqueueHttpError(401)

        try {
            engine.startSession()
            throw AssertionError("expected ApiException")
        } catch (_: ApiException) {
            // Expected.
        }
        engine.endSession()

        assertEquals("earlier successful session", TestEnv.app.memoryStore.todaySummary())
    }
}
