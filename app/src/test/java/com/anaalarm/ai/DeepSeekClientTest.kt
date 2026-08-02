package com.anaalarm.ai

import com.anaalarm.data.AppSettings
import com.anaalarm.data.SettingsStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeepSeekClientTest {

    private lateinit var server: MockWebServer
    private lateinit var settingsStore: SettingsStore
    private lateinit var client: DeepSeekClient

    /**
     * Addressed as an explicit IPv4 loopback rather than `server.url()`: on hosts where
     * "localhost" resolves to ::1 first, OkHttp burns both connect timeouts before failing.
     */
    private fun baseUrl(): String = "http://127.0.0.1:${server.port}/"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        settingsStore = mockk()
        every { settingsStore.settings } returns flowOf(AppSettings(apiKey = "test-key"))
        client = DeepSeekClient(settingsStore, DeepSeekClient.defaultApi(baseUrl = baseUrl()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `missing api key throws ApiException`() = runTest {
        every { settingsStore.settings } returns flowOf(AppSettings(apiKey = "  "))
        val noKeyClient = DeepSeekClient(settingsStore, DeepSeekClient.defaultApi(baseUrl = baseUrl()))
        try {
            noKeyClient.respond("sys", emptyList())
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("no api key", e.message)
        }
    }

    @Test
    fun `parses output_text and sends bearer auth`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"r1","output_text":"Good morning!"}""")
        )

        val text = client.respond("Be Ana", listOf(ChatMessageItem("user", "hi")))
        assertEquals("Good morning!", text)

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.path!!.endsWith("responses"))
        assertTrue(recorded.body.readUtf8().contains("deepseek-v4-flash"))
    }

    @Test
    fun `api error body becomes ApiException`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"error":{"message":"quota exceeded","type":"invalid_request"}}""")
        )
        try {
            client.respond("sys", emptyList())
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("quota exceeded", e.message)
        }
    }

    @Test
    fun `empty response throws`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"r1","output":[]}""")
        )
        try {
            client.respond("sys", emptyList())
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("empty response", e.message)
        }
    }

    @Test
    fun `http 401 maps to invalid api key`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        try {
            client.respond("sys", listOf(ChatMessageItem("user", "hi")))
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("invalid api key", e.message)
        }
    }
}
