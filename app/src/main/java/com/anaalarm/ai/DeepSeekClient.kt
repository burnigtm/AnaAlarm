package com.anaalarm.ai

import android.util.Log
import com.anaalarm.data.SettingsStore
import kotlinx.coroutines.flow.first
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
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessageItem(val role: String, val content: String)

@Serializable
data class ResponsesRequest(
    val model: String = "deepseek-v4-flash",
    val instructions: String? = null,
    val input: List<ChatMessageItem>? = null,
    val temperature: Double = 0.9,
    val stream: Boolean = false,
    @SerialName("max_output_tokens") val maxOutputTokens: Int = 400
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
data class ApiErrorBody(val message: String? = null, val type: String? = null)

@Serializable
data class ResponsesResponse(
    val id: String? = null,
    @SerialName("output_text") val outputText: String? = null,
    val output: List<OutputItem>? = null,
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
    private val api: DeepSeekApi = defaultApi()
) {

    suspend fun respond(instructions: String, history: List<ChatMessageItem>): String {
        val key = settingsStore.settings.first().apiKey.trim()
        if (key.isBlank()) throw ApiException("no api key")
        Log.i(TAG, "DeepSeek request apiKey=${maskKey(key)}")

        val request = ResponsesRequest(
            instructions = instructions,
            input = history.takeIf { it.isNotEmpty() },
            temperature = 0.9,
            maxOutputTokens = 400
        )

        var attempts = 0
        while (attempts < 2) {
            attempts++
            try {
                val response = api.createResponse("Bearer $key", request)
                if (response.error != null) {
                    throw ApiException(response.error.message ?: "API error")
                }
                val text = ResponseTextExtractor.extract(response)
                if (!text.isNullOrBlank()) return text
                throw ApiException("empty response")
            } catch (e: ApiException) {
                throw e
            } catch (e: HttpException) {
                throw ApiErrorMapper.fromThrowable(e)
            } catch (e: IOException) {
                val mapped = ApiErrorMapper.fromThrowable(e)
                // SSL / cert failures will not recover on retry.
                if (mapped.message == "ssl error" || attempts >= 2) throw mapped
            } catch (e: Exception) {
                throw ApiErrorMapper.fromThrowable(e)
            }
        }
        throw ApiException("request failed")
    }

    companion object {
        private const val TAG = "AnaAlarm"

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
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            return DebugTls.applyIfDebug(builder).build()
        }

        /** Masks a secret for logs: never prints the full key. */
        fun maskKey(key: String): String {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) return "missing"
            if (trimmed.length <= 8) return "set(len=${trimmed.length})"
            return "set(len=${trimmed.length}, ${trimmed.take(3)}…${trimmed.takeLast(2)})"
        }
    }
}
