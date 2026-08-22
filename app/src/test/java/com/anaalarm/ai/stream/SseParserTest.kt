package com.anaalarm.ai.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SseParserTest {

    @Test
    fun `single complete frame is dispatched`() {
        val parser = SseParser()
        val frames = parser.feed("event: response.output_text.delta\ndata: {\"a\":1}\n\n")
        assertEquals(listOf(SseFrame("response.output_text.delta", "{\"a\":1}")), frames)
    }

    @Test
    fun `chunks split anywhere produce the same frames`() {
        val payload = "event: e1\ndata: hello world\n\nevent: e2\ndata: second\n\n"
        val expected = listOf(SseFrame("e1", "hello world"), SseFrame("e2", "second"))
        // Feed one character at a time to prove arbitrary chunk boundaries are safe.
        val charWise = SseParser()
        val collected = mutableListOf<SseFrame>()
        payload.forEach { chunk -> collected += charWise.feed(chunk.toString()) }
        collected += charWise.finish()
        assertEquals(expected, collected)

        // And a single big chunk behaves identically.
        val oneShot = SseParser()
        assertEquals(expected, oneShot.feed(payload))
    }

    @Test
    fun `repeated data lines join with newline`() {
        val parser = SseParser()
        val frames = parser.feed("data: line one\ndata: line two\n\n")
        assertEquals(listOf(SseFrame(null, "line one\nline two")), frames)
    }

    @Test
    fun `keep-alive comments are ignored`() {
        val parser = SseParser()
        val frames = parser.feed(": keep-alive\n\n: another comment\ndata: real\n\n")
        assertEquals(listOf(SseFrame(null, "real")), frames)
    }

    @Test
    fun `crlf and bare cr terminate lines`() {
        val parser = SseParser()
        assertEquals(
            listOf(SseFrame("t", "a\nb")),
            parser.feed("event: t\r\ndata: a\rdata: b\r\n\r\n")
        )
    }

    @Test
    fun `cr at a chunk boundary is not a spurious blank line`() {
        val parser = SseParser()
        val frames = mutableListOf<SseFrame>()
        frames += parser.feed("data: x\r")
        frames += parser.feed("\ndata: y\n\n")
        assertEquals(listOf(SseFrame(null, "x\ny")), frames)
    }

    @Test
    fun `empty dispatch between comments is not a frame`() {
        val parser = SseParser()
        assertNull(parser.feed(": only a comment\n").singleOrNull())
        assertEquals(emptyList<SseFrame>(), parser.feed("\n"))
    }

    @Test
    fun `trailing unterminated frame is dispatched by finish`() {
        val parser = SseParser()
        parser.feed("data: tail-without-newline")
        assertEquals(listOf(SseFrame(null, "tail-without-newline")), parser.finish())
    }

    @Test
    fun `finish dispatches accumulated data after last line`() {
        val parser = SseParser()
        parser.feed("event: done\ndata: partial")
        assertEquals(listOf(SseFrame("done", "partial")), parser.finish())
    }

    @Test
    fun `oversized frame throws instead of growing without limit`() {
        val parser = SseParser(maxFrameChars = 16)
        assertThrows(SseProtocolException::class.java) {
            parser.feed("data: ${"x".repeat(32)}\n\n")
        }
    }

    @Test
    fun `id and retry fields are ignored without error`() {
        val parser = SseParser()
        assertEquals(
            listOf(SseFrame(null, "payload")),
            parser.feed("id: 42\nretry: 1000\ndata: payload\n\n")
        )
    }
}
