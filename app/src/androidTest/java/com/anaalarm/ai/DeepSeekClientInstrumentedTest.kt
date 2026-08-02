package com.anaalarm.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.anaalarm.support.FakeAiServer
import com.anaalarm.support.TestEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real Retrofit + OkHttp + kotlinx-serialization stack on the device against a
 * local stand-in for the DeepSeek Responses API.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class DeepSeekClientInstrumentedTest {

    private lateinit var server: FakeAiServer
    private lateinit var client: DeepSeekClient

    private val instructions = "You are Ana."
    private val input = listOf(ChatMessageItem("user", "Please begin the wake-up greeting now."))

    @Before
    fun setUp() {
        TestEnv.resetSettings()
        server = FakeAiServer()
        server.start()
        client = server.client()
    }

    @After
    fun tearDown() {
        server.shutdown()
        TestEnv.resetSettings()
    }

    private fun setApiKey(key: String) = runBlocking {
        TestEnv.app.settingsStore.update(apiKey = key)
    }

    private fun expectFailure(block: suspend () -> Unit): ApiException = runBlocking {
        try {
            block()
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            e
        }
    }

    @Test
    fun withoutAnApiKeyNoRequestIsMade() {
        val error = expectFailure { client.respond(instructions, input) }

        assertEquals("no api key", error.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun flatOutputTextIsReturned() = runBlocking {
        setApiKey("sk-test")
        server.enqueueReply("Good morning! How did you sleep?")

        val reply = client.respond(instructions, input)

        assertEquals("Good morning! How did you sleep?", reply)
    }

    @Test
    fun nestedOutputContentIsReturned() = runBlocking {
        setApiKey("sk-test")
        server.enqueueStructuredReply("Time to get up, sunshine.")

        assertEquals("Time to get up, sunshine.", client.respond(instructions, input))
    }

    @Test
    fun requestCarriesTheBearerTokenModelAndConversation() = runBlocking {
        setApiKey("sk-header-check")
        server.enqueueReply("ok")

        client.respond(instructions, input)

        val recorded = server.takeRequest()!!
        assertEquals("POST", recorded.method)
        assertEquals("/responses", recorded.path)
        assertEquals("Bearer sk-header-check", recorded.getHeader("Authorization"))

        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("deepseek-v4-flash", body.getString("model"))
        assertEquals(instructions, body.getString("instructions"))
        assertEquals(96, body.getInt("max_output_tokens"))
        assertEquals("none", body.getJSONObject("reasoning").getString("effort"))
        assertTrue(!body.getBoolean("stream"))

        val turns = body.getJSONArray("input")
        assertEquals(1, turns.length())
        assertEquals("user", turns.getJSONObject(0).getString("role"))
    }

    @Test
    fun rejectedCredentialsMapToInvalidApiKey() {
        setApiKey("sk-bad")

        server.enqueueHttpError(401)
        assertEquals("invalid api key", expectFailure { client.respond(instructions, input) }.message)

        server.enqueueHttpError(403)
        assertEquals("invalid api key", expectFailure { client.respond(instructions, input) }.message)
    }

    @Test
    fun otherHttpFailuresKeepTheirStatusCode() {
        setApiKey("sk-test")
        server.enqueueHttpError(500)

        assertEquals("HTTP 500", expectFailure { client.respond(instructions, input) }.message)
    }

    @Test
    fun errorObjectInsideA200IsSurfaced() {
        setApiKey("sk-test")
        server.enqueueApiError("Model not available")

        assertEquals(
            "Model not available",
            expectFailure { client.respond(instructions, input) }.message
        )
    }

    @Test
    fun aResponseWithoutTextIsTreatedAsAFailure() {
        setApiKey("sk-test")
        server.enqueueEmptyReply()

        assertEquals("empty response", expectFailure { client.respond(instructions, input) }.message)
    }

    @Test
    fun aDroppedConnectionIsRetried() = runBlocking {
        setApiKey("sk-test")
        server.enqueueDisconnect()
        server.enqueueReply("Recovered")
        server.enqueueReply("Recovered") // spare, in case OkHttp replays the request itself

        assertEquals("Recovered", client.respond(instructions, input))
        assertTrue(server.requestCount >= 1)
    }

    @Test
    fun anUnreachableServerReportsANetworkError() {
        setApiKey("sk-test")
        val deadClient = server.client()
        server.shutdown()

        assertEquals(
            "network error",
            expectFailure { deadClient.respond(instructions, input) }.message
        )
    }

    @Test
    fun apiKeysAreNeverLoggedInFull() {
        assertEquals("missing", DeepSeekClient.maskKey("   "))
        assertEquals("set(len=6)", DeepSeekClient.maskKey("sk-abc"))
        assertEquals("set(len=13)", DeepSeekClient.maskKey("sk-1234567890"))
    }
}
