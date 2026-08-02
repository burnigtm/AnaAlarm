package com.anaalarm.support

import com.anaalarm.ai.DeepSeekClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * A DeepSeek Responses API stand-in running inside the app process. Lets instrumented tests
 * drive real conversations (and real failures) over the real Retrofit/OkHttp stack without
 * network access or an API key with credit on it.
 */
class FakeAiServer {

    private val server = MockWebServer()

    /**
     * Bound to an explicit IPv4 loopback: where "localhost" resolves to ::1 first, OkHttp burns
     * a full connect timeout before falling back, which turns fast tests into minute-long hangs.
     */
    fun start() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    fun shutdown() {
        runCatching { server.shutdown() }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.port}/"

    val requestCount: Int get() = server.requestCount

    /** A client wired to this server, using the app's real settings store for the API key. */
    fun client(): DeepSeekClient =
        DeepSeekClient(TestEnv.app.settingsStore, DeepSeekClient.defaultApi(baseUrl))

    /** Points the whole app (including the wake-up session) at this server. */
    fun installIntoApp() {
        TestEnv.app.overrideAiBackend(client())
    }

    /** Flat `output_text` shape — what DeepSeek returns for simple replies. */
    fun enqueueReply(text: String) = enqueue(
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("id", "resp_flat").put("output_text", text).toString())
    )

    /** Nested `output[].content[]` shape — the fuller Responses API envelope. */
    fun enqueueStructuredReply(text: String) {
        val part = JSONObject().put("type", "output_text").put("text", text)
        val message = JSONObject()
            .put("type", "message")
            .put("role", "assistant")
            .put("content", JSONArray().put(part))
        val body = JSONObject()
            .put("id", "resp_structured")
            .put("output", JSONArray().put(message))
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body.toString())
        )
    }

    fun enqueueEmptyReply() = enqueue(
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("id", "resp_empty").toString())
    )

    fun enqueueApiError(message: String) = enqueue(
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                JSONObject()
                    .put("id", "resp_error")
                    .put("error", JSONObject().put("message", message).put("type", "invalid_request"))
                    .toString()
            )
    )

    fun enqueueHttpError(code: Int, message: String = "boom") = enqueue(
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("message", message).toString())
    )

    /** Simulates a dropped connection, which surfaces as IOException in OkHttp. */
    fun enqueueDisconnect() = enqueue(
        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
    )

    /** Always answers, for sessions whose exact turn count is not deterministic. */
    fun respondAlwaysWith(text: String) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("id", "resp_any").put("output_text", text).toString())
        }
    }

    fun takeRequest(timeoutMs: Long = 10_000): RecordedRequest? =
        server.takeRequest(timeoutMs, TimeUnit.MILLISECONDS)

    private fun enqueue(response: MockResponse) {
        server.enqueue(response)
    }
}
