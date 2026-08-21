package com.anaalarm.ai

import com.anaalarm.data.AppSettings
import com.anaalarm.data.SettingsStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class DeepSeekClientStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var settingsStore: SettingsStore
    private lateinit var client: DeepSeekClient

    private fun baseUrl(): String = "http://127.0.0.1:${server.port}/"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        settingsStore = mockk()
        every { settingsStore.settings } returns flowOf(AppSettings(apiKey = "test-key"))
        client = DeepSeekClient(
            settingsStore = settingsStore,
            api = DeepSeekClient.defaultApi(baseUrl = baseUrl())
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun delta(text: String, sequence: Long) =
        """{"type":"response.output_text.delta","delta":"$text","sequence_number":$sequence}"""

    private fun completed(sequence: Long, withUsage: Boolean = false): String {
        val usage = if (withUsage) {
            // Single line: literal newlines inside one data: frame would break SSE framing.
            "," + "\"usage\":{\"input_tokens\":120,\"output_tokens\":18," +
                "\"total_tokens\":138,\"input_tokens_details\":{\"cached_tokens\":90}}"
        } else ""
        return """{"type":"response.completed","sequence_number":$sequence,"response":{"id":"r1"$usage}}"""
    }

    private fun sseResponse(vararg frames: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(frames.joinToString(separator = "") { frame -> "data: $frame\n\n" })

    @Test
    fun `happy stream returns canonical text in delta order`() = runBlocking {
        server.enqueue(
            sseResponse(
                delta("Good", 0),
                delta(" morning", 1),
                delta(", sleepyhead.", 2),
                completed(3, withUsage = true)
            )
        )

        val deltas = mutableListOf<String>()
        val reply = client.streamRespond("sys", emptyList(), onTextDelta = { deltas += it })

        assertEquals("Good morning, sleepyhead.", reply.text)
        assertEquals(listOf("Good", " morning", ", sleepyhead."), deltas)
        // Usage reported by the terminal completed event reaches the caller.
        assertEquals(120L, reply.usage?.inputTokens)
        assertEquals(18L, reply.usage?.outputTokens)
        assertEquals(90L, reply.usage?.inputTokenDetails?.cachedTokens)

        val recorded = server.takeRequest()
        assertEquals("/responses", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun `eof without a terminal event is a failed stream`() {
        server.enqueue(
            sseResponse(delta("partial answer that never completes", 0))
        )

        val error = try {
            runBlocking { client.streamRespond("sys", emptyList(), onTextDelta = {}) }
            throw AssertionError("expected StreamFailedException")
        } catch (e: StreamFailedException) {
            e
        }
        assertEquals("stream ended without completing", error.detail)
    }

    @Test
    fun `failed terminal surfaces its message`() {
        server.enqueue(
            sseResponse(
                delta("hi", 0),
                """{"type":"response.failed","sequence_number":1,"response":{"error":{"message":"model overloaded"}}}"""
            )
        )

        val error = try {
            runBlocking { client.streamRespond("sys", emptyList(), onTextDelta = {}) }
            throw AssertionError("expected StreamFailedException")
        } catch (e: StreamFailedException) {
            e
        }
        assertEquals("model overloaded", error.detail)
    }

    @Test
    fun `incomplete terminal surfaces its reason`() {
        server.enqueue(
            sseResponse(
                delta("trunca", 0),
                """{"type":"response.incomplete","sequence_number":1,"response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""
            )
        )

        val error = try {
            runBlocking { client.streamRespond("sys", emptyList(), onTextDelta = {}) }
            throw AssertionError("expected StreamIncompleteException")
        } catch (e: StreamIncompleteException) {
            e
        }
        assertEquals("max_output_tokens", error.reason)
    }

    @Test
    fun `protocol violation is reported as a failed stream`() {
        // Regressing sequence number after a valid delta.
        server.enqueue(
            sseResponse(
                delta("a", 5),
                delta("b", 4)
            )
        )

        val error = try {
            runBlocking { client.streamRespond("sys", emptyList(), onTextDelta = {}) }
            throw AssertionError("expected StreamFailedException")
        } catch (e: StreamFailedException) {
            e
        }
        assertEquals("stream protocol error", error.detail)
    }

    @Test
    fun `non event-stream content type is rejected`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"completed"}""")
        )

        val error = try {
            runBlocking { client.streamRespond("sys", emptyList(), onTextDelta = {}) }
            throw AssertionError("expected StreamFailedException")
        } catch (e: StreamFailedException) {
            e
        }
        assertEquals("stream protocol error", error.detail)
    }

    @Test
    fun `http 401 on the stream endpoint maps to invalid api key`() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}""")
        )

        val message = try {
            runBlocking { client.streamRespond("sys", emptyList(), onTextDelta = {}) }
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            e.message
        }
        assertEquals("invalid api key", message)
    }

    @Test
    fun `missing api key fails before any request`() {
        every { settingsStore.settings } returns flowOf(AppSettings(apiKey = "  "))

        val message = try {
            runBlocking { client.streamRespond("sys", emptyList(), onTextDelta = {}) }
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            e.message
        }
        assertEquals("no api key", message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `chunked transport is read incrementally to the same result`() = runBlocking {
        val body = buildString {
            append("data: ${delta("chunk one. ", 0)}\n\n")
            append(": keep-alive\n\n")
            append("data: ${delta("chunk two.", 1)}\n\n")
            append("data: ${completed(2)}\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                .setBody(body)
        )

        val deltas = mutableListOf<String>()
        val reply = client.streamRespond("sys", emptyList(), onTextDelta = { deltas += it })

        assertEquals("chunk one. chunk two.", reply.text)
        assertEquals(listOf("chunk one. ", "chunk two."), deltas)
    }

    @Test
    fun `caller cancellation closes the stream`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )
        val job = launch {
            client.streamRespond("sys", emptyList(), onTextDelta = {})
        }
        yield()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }
}
