package com.anaalarm.ai.stream

import com.anaalarm.ai.ResponseUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Provider-neutral events decoded from Responses SSE frames. Only [TextDelta] carries visible
 * assistant text; every other payload is control information and must never reach TTS.
 */
internal sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class Completed(val usage: ResponseUsage?) : StreamEvent
    data class Incomplete(val reason: String?) : StreamEvent
    data class Failed(val message: String?) : StreamEvent
}

/**
 * Turns validated SSE frames into semantic Responses stream events.
 *
 * Contract (docs/RELIABILITY_AND_LATENCY.md):
 * - Every event must carry a strictly increasing `sequence_number`; duplicates or regressions
 *   are protocol errors.
 * - A present `event:` name must match the JSON `type`.
 * - The Chat Completions `data: [DONE]` sentinel is explicitly rejected on /responses.
 * - Exactly one terminal event (`response.completed|incomplete|failed`) is accepted; a second
 *   one is a protocol error.
 * - Unknown future event types are ignored, but only after their sequence number was valid.
 */
internal class ResponsesStreamDecoder(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private var lastSequenceNumber = -1L
    private var terminalSeen = false

    fun decode(frame: SseFrame): StreamEvent? {
        if (frame.data.trim() == DONE_SENTINEL) {
            throw SseProtocolException("chat-completions [DONE] sentinel is not valid on /responses")
        }

        val payload = runCatching { json.parseToJsonElement(frame.data).jsonObject }
            .getOrElse { throw SseProtocolException("frame payload is not a JSON object") }

        val type = payload.stringOrNull("type")
            ?: throw SseProtocolException("event without a type")

        frame.event?.takeIf { it.isNotEmpty() }?.let { declared ->
            if (declared != type) throw SseProtocolException("event '$declared' does not match type '$type'")
        }

        val sequenceNumber = payload.longOrNull(SEQUENCE_FIELD)
            ?: throw SseProtocolException("event without a valid sequence_number")
        if (sequenceNumber <= lastSequenceNumber) {
            throw SseProtocolException("non-increasing sequence_number $sequenceNumber")
        }
        lastSequenceNumber = sequenceNumber

        return when (type) {
            TYPE_TEXT_DELTA -> StreamEvent.TextDelta(deltaText(payload))
            TYPE_COMPLETED -> {
                requireFirstTerminal(type)
                StreamEvent.Completed(payload.nestedObject(RESPONSE_FIELD)?.let(::extractUsage))
            }
            TYPE_INCOMPLETE -> {
                requireFirstTerminal(type)
                val nested = payload.nestedObject(RESPONSE_FIELD)
                StreamEvent.Incomplete(
                    (nested ?: payload).nestedObject(INCOMPLETE_DETAILS_FIELD)
                        ?.stringOrNull(REASON_FIELD)
                )
            }
            TYPE_FAILED -> {
                requireFirstTerminal(type)
                val nested = payload.nestedObject(RESPONSE_FIELD)
                StreamEvent.Failed(
                    ((nested ?: payload).nestedObject(ERROR_FIELD))?.stringOrNull(MESSAGE_FIELD)
                )
            }
            else -> null
        }
    }

    private fun requireFirstTerminal(type: String) {
        check(!terminalSeen) { "second terminal event of type $type" }
        terminalSeen = true
    }

    private fun deltaText(payload: JsonObject): String =
        payload.stringOrNull(DELTA_FIELD)
            ?: throw SseProtocolException("output_text.delta without a string delta")

    /** Reads `usage` tolerantly from either the raw completed event or its nested response. */
    private fun extractUsage(source: JsonObject): ResponseUsage? =
        source.nestedObject(USAGE_FIELD)?.let { usageJson ->
            runCatching {
                json.decodeFromJsonElement(ResponseUsage.serializer(), usageJson)
            }.getOrNull()
        }

    private fun JsonObject.stringOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.longOrNull(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.nestedObject(name: String): JsonObject? =
        (this[name] as? JsonObject)?.jsonObject

    companion object {
        const val DONE_SENTINEL = "[DONE]"
        const val TYPE_TEXT_DELTA = "response.output_text.delta"
        const val TYPE_COMPLETED = "response.completed"
        const val TYPE_INCOMPLETE = "response.incomplete"
        const val TYPE_FAILED = "response.failed"
        private const val DELTA_FIELD = "delta"
        private const val SEQUENCE_FIELD = "sequence_number"
        private const val RESPONSE_FIELD = "response"
        private const val USAGE_FIELD = "usage"
        private const val INCOMPLETE_DETAILS_FIELD = "incomplete_details"
        private const val REASON_FIELD = "reason"
        private const val ERROR_FIELD = "error"
        private const val MESSAGE_FIELD = "message"
    }
}
