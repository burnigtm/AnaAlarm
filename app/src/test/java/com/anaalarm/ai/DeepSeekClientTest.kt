package com.anaalarm.ai

import com.anaalarm.data.AppSettings
import com.anaalarm.data.SettingsStore
import com.anaalarm.telemetry.LatencyEvent
import com.anaalarm.telemetry.LatencyEventSink
import com.anaalarm.telemetry.LatencyMetric
import com.anaalarm.telemetry.LatencyMetrics
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class DeepSeekClientTest {

    private lateinit var server: MockWebServer
    private lateinit var settingsStore: SettingsStore
    private lateinit var client: DeepSeekClient

    /**
     * Explicit IPv4 avoids a slow ::1 fallback on hosts where MockWebServer bound only IPv4.
     */
    private fun baseUrl(): String = "http://127.0.0.1:${server.port}/"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        settingsStore = mockk()
        every { settingsStore.settings } returns flowOf(AppSettings(apiKey = "test-key"))
        client = newClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(
        timeoutMillis: Long = DeepSeekClient.DEFAULT_REQUEST_TIMEOUT_MILLIS,
        api: DeepSeekApi = DeepSeekClient.defaultApi(baseUrl = baseUrl())
    ) =
        DeepSeekClient(
            settingsStore = settingsStore,
            api = api,
            requestTimeoutMillis = timeoutMillis
        )

    private fun expectApiException(block: suspend () -> Unit): ApiException = runBlocking {
        try {
            block()
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            e
        }
    }

    @Test
    fun `missing api key fails before making a request`() {
        every { settingsStore.settings } returns flowOf(AppSettings(apiKey = "  "))

        assertEquals("no api key", expectApiException { newClient().respond("sys", emptyList()) }.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `request disables reasoning and constrains spoken output`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{
                        "id":"r1",
                        "status":"completed",
                        "output_text":"Good morning!",
                        "usage":{
                          "input_tokens":120,
                          "output_tokens":18,
                          "total_tokens":138,
                          "input_tokens_details":{"cached_tokens":90},
                          "output_tokens_details":{"reasoning_tokens":0}
                        }
                    }""".trimIndent()
                )
        )

        val reply = client.respond("Be Ana", listOf(ChatMessageItem("user", "hi")))
        assertEquals("Good morning!", reply.text)
        // Provider-reported usage must reach the caller for the usage dashboard.
        assertEquals(120L, reply.usage?.inputTokens)
        assertEquals(18L, reply.usage?.outputTokens)
        assertEquals(138L, reply.usage?.totalTokens)
        assertEquals(90L, reply.usage?.inputTokenDetails?.cachedTokens)

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertEquals("/responses", recorded.path)

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("deepseek-v4-flash", body.getValue("model").jsonPrimitive.content)
        assertEquals("Be Ana", body.getValue("instructions").jsonPrimitive.content)
        assertEquals(96, body.getValue("max_output_tokens").jsonPrimitive.int)
        assertEquals(false, body.getValue("stream").jsonPrimitive.boolean)
        assertEquals(
            "none",
            body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content
        )
        val input = body.getValue("input").jsonArray
        assertEquals(1, input.size)
        assertEquals("user", input[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("hi", input[0].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `incomplete response surfaces its structured reason even when partial text exists`() {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "id":"r2",
                    "status":"incomplete",
                    "incomplete_details":{"reason":"max_output_tokens"},
                    "output_text":"truncated text"
                }""".trimIndent()
            )
        )

        assertEquals(
            "incomplete response: max_output_tokens",
            expectApiException { client.respond("sys", emptyList()) }.message
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `failed response surfaces the API error without retrying`() {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "id":"r3",
                    "status":"failed",
                    "error":{"message":"model overloaded","type":"server_error","code":"busy"}
                }""".trimIndent()
            )
        )

        assertEquals(
            "model overloaded",
            expectApiException { client.respond("sys", emptyList()) }.message
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `failed response without error has a stable fallback`() {
        server.enqueue(MockResponse().setBody("""{"id":"r4","status":"failed"}"""))

        assertEquals(
            "response failed",
            expectApiException { client.respond("sys", emptyList()) }.message
        )
    }

    @Test
    fun `empty completed response is rejected`() {
        server.enqueue(MockResponse().setBody("""{"id":"r5","status":"completed","output":[]}"""))

        assertEquals("empty response", expectApiException { client.respond("sys", emptyList()) }.message)
    }

    @Test
    fun `http 401 maps to invalid api key without retrying`() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}""")
        )

        assertEquals(
            "invalid api key",
            expectApiException {
                client.respond("sys", listOf(ChatMessageItem("user", "hi")))
            }.message
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `quick connection loss is retried once`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"retry-ok","status":"completed","output_text":"Recovered"}"""
            )
        )

        assertEquals("Recovered", client.respond("sys", emptyList()).text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `whole operation deadline includes retry budget`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortDeadlineClient = newClient(timeoutMillis = 150)
        val started = System.nanoTime()

        assertEquals(
            "network error",
            expectApiException { shortDeadlineClient.respond("sys", emptyList()) }.message
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("deadline should fail promptly, elapsed=$elapsedMillis", elapsedMillis < 3_000)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `caller cancellation is preserved and never retried`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val response = async { client.respond("sys", emptyList()) }
        yield() // Let the child reach Retrofit before this thread blocks in takeRequest().
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        response.cancel()
        try {
            response.await()
            throw AssertionError("expected CancellationException")
        } catch (_: CancellationException) {
            // Expected: cancellation must not be translated into ApiException.
        }

        assertTrue(response.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `callers shorter timeout is preserved as cancellation`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        try {
            withTimeout(100) {
                client.respond("sys", emptyList())
            }
            throw AssertionError("expected TimeoutCancellationException")
        } catch (_: TimeoutCancellationException) {
            // The caller owns this deadline; the client must not translate it to ApiException.
        }

        assertEquals(1, server.requestCount)
    }

    /** Streaming is out of scope for the legacy-path tests; any call here is a test bug. */
    private fun unsupportedStream(): okhttp3.ResponseBody =
        error("createResponseStream must not be called by this test")

    @Test
    fun `successful request emits exactly one completed model metric`() = runTest {
        val events = mutableListOf<LatencyEvent>()
        val fakeApi = object : DeepSeekApi {
            override suspend fun createResponse(
                authorization: String,
                request: ResponsesRequest
            ) = completedResponse("Good morning")

            override suspend fun createResponseStream(
                authorization: String,
                request: ResponsesRequest
            ): okhttp3.ResponseBody = unsupportedStream()
        }

        LatencyMetrics.withEventSink(LatencyEventSink { events.add(it) }) {
            assertEquals("Good morning", newClient(api = fakeApi).respond("sys", emptyList()).text)
        }

        assertSingleModelEvent(events, outcome = "completed", attempts = 1)
    }

    @Test
    fun `retry success still emits one terminal model metric`() = runTest {
        val events = mutableListOf<LatencyEvent>()
        var calls = 0
        val fakeApi = object : DeepSeekApi {
            override suspend fun createResponse(
                authorization: String,
                request: ResponsesRequest
            ): ResponsesResponse {
                calls++
                if (calls == 1) throw IOException("connection reset")
                return completedResponse("Recovered")
            }

            override suspend fun createResponseStream(
                authorization: String,
                request: ResponsesRequest
            ): okhttp3.ResponseBody = unsupportedStream()
        }

        LatencyMetrics.withEventSink(LatencyEventSink { events.add(it) }) {
            assertEquals("Recovered", newClient(api = fakeApi).respond("sys", emptyList()).text)
        }

        assertEquals(2, calls)
        assertSingleModelEvent(events, outcome = "completed", attempts = 2)
    }

    @Test
    fun `provider failure emits exactly one failed model metric`() = runTest {
        val events = mutableListOf<LatencyEvent>()
        val fakeApi = object : DeepSeekApi {
            override suspend fun createResponse(
                authorization: String,
                request: ResponsesRequest
            ) = ResponsesResponse(status = "failed")

            override suspend fun createResponseStream(
                authorization: String,
                request: ResponsesRequest
            ): okhttp3.ResponseBody = unsupportedStream()
        }

        val error = try {
            LatencyMetrics.withEventSink(LatencyEventSink { events.add(it) }) {
                newClient(api = fakeApi).respond("sys", emptyList())
            }
            throw AssertionError("expected ApiException")
        } catch (expected: ApiException) {
            expected
        }

        assertEquals("response failed", error.message)
        assertSingleModelEvent(events, outcome = "failed", attempts = 1)
    }

    @Test
    fun `operation timeout emits exactly one timed out model metric`() = runTest {
        val events = mutableListOf<LatencyEvent>()
        val fakeApi = object : DeepSeekApi {
            override suspend fun createResponse(
                authorization: String,
                request: ResponsesRequest
            ): ResponsesResponse = awaitCancellation()

            override suspend fun createResponseStream(
                authorization: String,
                request: ResponsesRequest
            ): okhttp3.ResponseBody = unsupportedStream()
        }

        val error = try {
            LatencyMetrics.withEventSink(LatencyEventSink { events.add(it) }) {
                newClient(timeoutMillis = 100, api = fakeApi).respond("sys", emptyList())
            }
            throw AssertionError("expected ApiException")
        } catch (expected: ApiException) {
            expected
        }

        assertEquals("network error", error.message)
        assertSingleModelEvent(events, outcome = "timed_out", attempts = 1)
    }

    @Test
    fun `caller cancellation emits exactly one cancelled model metric`() = runTest {
        val events = mutableListOf<LatencyEvent>()
        val enteredApi = CompletableDeferred<Unit>()
        val fakeApi = object : DeepSeekApi {
            override suspend fun createResponse(
                authorization: String,
                request: ResponsesRequest
            ): ResponsesResponse {
                enteredApi.complete(Unit)
                awaitCancellation()
            }

            override suspend fun createResponseStream(
                authorization: String,
                request: ResponsesRequest
            ): okhttp3.ResponseBody = unsupportedStream()
        }

        LatencyMetrics.withEventSink(LatencyEventSink { events.add(it) }) {
            val response = async { newClient(api = fakeApi).respond("sys", emptyList()) }
            enteredApi.await()
            response.cancelAndJoin()
            assertTrue(response.isCancelled)
        }

        assertSingleModelEvent(events, outcome = "cancelled", attempts = 1)
    }

    @Test
    fun `default transport enforces twenty second whole call timeout`() {
        val okHttp = DeepSeekClient.defaultOkHttp()

        assertEquals(20_000, okHttp.callTimeoutMillis)
        assertEquals(10_000, okHttp.connectTimeoutMillis)
        assertEquals(20_000, okHttp.readTimeoutMillis)
        assertEquals(10_000, okHttp.writeTimeoutMillis)
        assertEquals(false, okHttp.retryOnConnectionFailure)
    }

    private fun completedResponse(text: String) = ResponsesResponse(
        status = "completed",
        outputText = text
    )

    private fun assertSingleModelEvent(
        events: List<LatencyEvent>,
        outcome: String,
        attempts: Int
    ) {
        val modelEvents = events.filter {
            it.metric == LatencyMetric.MODEL_REQUEST_TO_COMPLETION
        }
        assertEquals(1, modelEvents.size)
        assertEquals(outcome, modelEvents.single().outcome)
        assertEquals(attempts.toString(), modelEvents.single().dimensions["attempts"])
    }
}
