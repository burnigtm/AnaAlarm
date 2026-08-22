package com.anaalarm.ai.stream

import com.anaalarm.ai.ResponseUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponsesStreamDecoderTest {

    private fun frame(data: String, event: String? = null) = SseFrame(event, data)

    private fun deltaJson(text: String, sequence: Long) =
        """{"type":"response.output_text.delta","delta":"$text","sequence_number":$sequence}"""

    @Test
    fun `text deltas are decoded in order`() {
        val decoder = ResponsesStreamDecoder()
        assertEquals(
            StreamEvent.TextDelta("Good"),
            decoder.decode(frame(deltaJson("Good", 0)))
        )
        assertEquals(
            StreamEvent.TextDelta(" morning"),
            decoder.decode(frame(deltaJson(" morning", 1)))
        )
    }

    @Test
    fun `event name must match the json type`() {
        val decoder = ResponsesStreamDecoder()
        assertThrows(SseProtocolException::class.java) {
            decoder.decode(frame(deltaJson("x", 0), event = "response.completed"))
        }
    }

    @Test
    fun `missing sequence number is a protocol error`() {
        val decoder = ResponsesStreamDecoder()
        assertThrows(SseProtocolException::class.java) {
            decoder.decode(frame("""{"type":"response.output_text.delta","delta":"x"}"""))
        }
    }

    @Test
    fun `duplicate or regressing sequence numbers are rejected`() {
        val decoder = ResponsesStreamDecoder()
        decoder.decode(frame(deltaJson("a", 5)))
        assertThrows(SseProtocolException::class.java) {
            decoder.decode(frame(deltaJson("b", 5)))
        }
        assertThrows(SseProtocolException::class.java) {
            decoder.decode(frame(deltaJson("c", 4)))
        }
    }

    @Test
    fun `unknown future events are ignored after their sequence validates`() {
        val decoder = ResponsesStreamDecoder()
        assertNull(
            decoder.decode(
                frame("""{"type":"response.future_thing","sequence_number":1,"junk":true}""")
            )
        )
        // The stream continues normally afterwards.
        assertEquals(
            StreamEvent.TextDelta("ok"),
            decoder.decode(frame(deltaJson("ok", 2)))
        )
    }

    @Test
    fun `chat completions done sentinel is explicitly rejected`() {
        val decoder = ResponsesStreamDecoder()
        assertThrows(SseProtocolException::class.java) {
            decoder.decode(frame("[DONE]"))
        }
    }

    @Test
    fun `non-json payload is a protocol error`() {
        val decoder = ResponsesStreamDecoder()
        assertThrows(SseProtocolException::class.java) {
            decoder.decode(frame("not json at all"))
        }
    }

    @Test
    fun `completed terminal carries usage when present`() {
        val decoder = ResponsesStreamDecoder()
        val event = decoder.decode(
            frame(
                """{"type":"response.completed","sequence_number":9,
                    "response":{"id":"r1","usage":{"input_tokens":10,"output_tokens":3,"total_tokens":13}}}"""
            )
        )
        assertEquals(
            StreamEvent.Completed(ResponseUsage(inputTokens = 10, outputTokens = 3, totalTokens = 13)),
            event
        )
    }

    @Test
    fun `incomplete terminal surfaces nested reason`() {
        val decoder = ResponsesStreamDecoder()
        val event = decoder.decode(
            frame(
                """{"type":"response.incomplete","sequence_number":2,
                    "response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""
            )
        )
        assertEquals(StreamEvent.Incomplete("max_output_tokens"), event)
    }

    @Test
    fun `failed terminal surfaces nested message`() {
        val decoder = ResponsesStreamDecoder()
        val event = decoder.decode(
            frame(
                """{"type":"response.failed","sequence_number":3,
                    "response":{"error":{"message":"model overloaded"}}}"""
            )
        )
        assertEquals(StreamEvent.Failed("model overloaded"), event)
    }

    @Test
    fun `second terminal is a protocol error`() {
        val decoder = ResponsesStreamDecoder()
        decoder.decode(frame("""{"type":"response.completed","sequence_number":1}"""))
        assertThrows(IllegalStateException::class.java) {
            decoder.decode(frame("""{"type":"response.failed","sequence_number":2}"""))
        }
    }

    @Test
    fun `delta without a string delta field is rejected`() {
        val decoder = ResponsesStreamDecoder()
        assertThrows(SseProtocolException::class.java) {
            decoder.decode(frame("""{"type":"response.output_text.delta","sequence_number":0}"""))
        }
    }

    @Test
    fun `event without a type is rejected`() {
        val decoder = ResponsesStreamDecoder()
        assertThrows(SseProtocolException::class.java) {
            decoder.decode(frame("""{"sequence_number":0,"delta":"x"}"""))
        }
    }
}
