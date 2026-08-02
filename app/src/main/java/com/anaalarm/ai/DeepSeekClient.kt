package com.anaalarm.ai

import android.util.Log
import com.anaalarm.data.SettingsStore
import com.anaalarm.telemetry.LatencyBoundary
import com.anaalarm.telemetry.LatencyMetric
import com.anaalarm.telemetry.LatencyMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class ChatMessageItem(val role: String, val content: String)

@Serializable
data class ReasoningOptions(val effort: String = "none")

@Serializable
data class ResponsesRequest(
    val model: String = "deepseek-v4-flash",
    val instructions: String? = null,
    val input: List<ChatMessageItem>? = null,
    val temperature: Double = 0.9,
    val reasoning: ReasoningOptions = ReasoningOptions(),
    val stream: Boolean = false,
    @SerialName("max_output_tokens") val maxOutputTokens: Int = 96
)

@Serializable
data class ContentPart(
    val type: String? = null,
    val text: String? = null,
    @SerialName("output_text") val outputText: String? = null
)

@Serializable
data class OutputItem(
    val type: String? = null,
    val role: String? = null,
    val content: List<ContentPart>? = null
)

@Serializable
data class ApiErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)

@Serializable
data class IncompleteDetails(val reason: String? = null)

@Serializable
data class InputTokenDetails(
    @SerialName("cached_tokens") val cachedTokens: Long? = null
)

@Serializable
data class OutputTokenDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null
)

@Serializable
data class ResponseUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("input_tokens_details") val inputTokenDetails: InputTokenDetails? = null,
    @SerialName("output_tokens_details") val outputTokenDetails: OutputTokenDetails? = null
)

@Serializable
data class ResponsesResponse(
    val id: String? = null,
    val status: String? = null,
    @SerialName("output_text") val outputText: String? = null,
    val output: List<OutputItem>? = null,
    @SerialName("incomplete_details") val incompleteDetails: IncompleteDetails? = null,
    val usage: ResponseUsage? = null,
    val error: ApiErrorBody? = null
)

interface DeepSeekApi {
    @POST("responses")
    suspend fun createResponse(
        @Header("Authorization") authorization: String,
        @Body request: ResponsesRequest
    ): ResponsesResponse
}

class ApiException(message: String) : Exception(message)

class DeepSeekClient(
    private val settingsStore: SettingsStore,
    private val api: DeepSeekApi = defaultApi(),
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS
) {

    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
    }

    suspend fun respond(instructions: String, history: List<ChatMessageItem>): String {
        val key = settingsStore.settings.first().apiKey.trim()
        if (key.isBlank()) throw ApiException("no api key")

        val request = ResponsesRequest(
            instructions = instructions,
            input = history.takeIf { it.isNotEmpty() },
            temperature = 0.9,
            reasoning = ReasoningOptions(effort = "none"),
            maxOutputTokens = MAX_OUTPUT_TOKENS
        )

        val requestId = requestSequence.incrementAndGet()
        val startedNanos = LatencyMetrics.nowNanos()
        val terminalLatency = LatencyBoundary(
            metric = LatencyMetric.MODEL_REQUEST_TO_COMPLETION,
            startedAtNanos = startedNanos
        )
        var attempts = 0
        Log.i(
            TAG,
            "DeepSeek request started apiKey=${maskKey(key)} inputItems=${history.size} " +
                "timeoutMs=$requestTimeoutMillis"
        )

        val result = try {
            withTimeoutOrNull(requestTimeoutMillis) {
                while (attempts < MAX_ATTEMPTS) {
                    currentCoroutineContext().ensureActive()
                    attempts++
                    try {
                        val response = api.createResponse("Bearer $key", request)
                        logResponseMetadata(response, attempts, elapsedMillis(startedNanos))
                        val text = requireCompletedText(response)
                        terminalLatency.record(
                            outcome = "completed",
                            "attempts" to attempts,
                            "input_items" to history.size,
                            "request_id" to requestId,
                            "status" to (response.status ?: "unspecified")
                        )
                        return@withTimeoutOrNull text
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        throw e
                    } catch (e: HttpException) {
                        throw ApiErrorMapper.fromThrowable(e)
                    } catch (e: IOException) {
                        // Retrofit can surface cancellation as IOException on some transports.
                        // Re-check the coroutine before deciding whether a retry is allowed.
                        currentCoroutineContext().ensureActive()
                        val mapped = ApiErrorMapper.fromThrowable(e)
                        val retry = attempts < MAX_ATTEMPTS &&
                            mapped.message != "ssl error" &&
                            e !is InterruptedIOException
                        Log.w(
                            TAG,
                            "DeepSeek transport failure attempt=$attempts retry=$retry " +
                                "durationMs=${elapsedMillis(startedNanos)}"
                        )
                        if (!retry) throw mapped
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        throw ApiErrorMapper.fromThrowable(e)
                    }
                }
                throw ApiException("request failed")
            }
        } catch (e: CancellationException) {
            terminalLatency.record(
                outcome = "cancelled",
                "attempts" to attempts,
                "request_id" to requestId
            )
            Log.i(
                TAG,
                "DeepSeek request cancelled attempts=$attempts " +
                    "durationMs=${elapsedMillis(startedNanos)}"
            )
            throw e
        } catch (e: ApiException) {
            terminalLatency.record(
                outcome = "failed",
                "attempts" to attempts,
                "error_type" to failureType(e),
                "request_id" to requestId
            )
            Log.w(
                TAG,
                "DeepSeek request failed attempts=$attempts " +
                    "durationMs=${elapsedMillis(startedNanos)}"
            )
            throw e
        }

        if (result == null) {
            terminalLatency.record(
                outcome = "timed_out",
                "attempts" to attempts,
                "request_id" to requestId
            )
            Log.w(
                TAG,
                "DeepSeek request timed out attempts=$attempts " +
                    "durationMs=${elapsedMillis(startedNanos)}"
            )
            // Keep the existing user-facing network-error contract.
            throw ApiException("network error")
        }
        return result
    }

    companion object {
        private const val TAG = "AnaAlarm"
        private const val MAX_ATTEMPTS = 2
        private const val MAX_OUTPUT_TOKENS = 96
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 20_000L
        private val requestSequence = AtomicLong(0L)

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        fun defaultApi(
            baseUrl: String = "https://api.deepseek.com/",
            client: OkHttpClient = defaultOkHttp()
        ): DeepSeekApi =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(DeepSeekApi::class.java)

        fun defaultOkHttp(): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .callTimeout(DEFAULT_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            return DebugTls.applyIfDebug(builder).build()
        }

        /** Reports only presence and length; no key characters are ever included. */
        fun maskKey(key: String): String {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) return "missing"
            return "set(len=${trimmed.length})"
        }

        private fun requireCompletedText(response: ResponsesResponse): String {
            response.error?.let { error ->
                throw ApiException(error.message?.takeIf { it.isNotBlank() } ?: "API error")
            }

            when (response.status?.lowercase()) {
                null, "completed" -> Unit
                "incomplete" -> {
                    val reason = response.incompleteDetails?.reason?.takeIf { it.isNotBlank() }
                    throw ApiException(
                        if (reason == null) "incomplete response" else "incomplete response: $reason"
                    )
                }
                "failed" -> throw ApiException("response failed")
                else -> throw ApiException("unexpected response status")
            }

            return ResponseTextExtractor.extract(response)
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiException("empty response")
        }

        private fun logResponseMetadata(
            response: ResponsesResponse,
            attempts: Int,
            durationMillis: Long
        ) {
            val usage = response.usage
            Log.i(
                TAG,
                "DeepSeek response status=${response.status ?: "unspecified"} " +
                    "attempts=$attempts durationMs=$durationMillis " +
                    "inputTokens=${usage?.inputTokens ?: "unknown"} " +
                    "cachedTokens=${usage?.inputTokenDetails?.cachedTokens ?: "unknown"} " +
                    "outputTokens=${usage?.outputTokens ?: "unknown"} " +
                    "reasoningTokens=${usage?.outputTokenDetails?.reasoningTokens ?: "unknown"} " +
                    "totalTokens=${usage?.totalTokens ?: "unknown"}"
            )
        }

        private fun elapsedMillis(startedNanos: Long): Long =
            LatencyMetrics.durationMillis(startedNanos, LatencyMetrics.nowNanos())

        private fun failureType(error: ApiException): String = when (error.message) {
            "invalid api key" -> "authentication"
            "ssl error" -> "tls"
            "network error" -> "network"
            "empty response" -> "empty_response"
            else -> "api"
        }
    }
}
