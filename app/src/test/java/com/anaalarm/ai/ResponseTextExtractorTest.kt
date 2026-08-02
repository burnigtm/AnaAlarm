package com.anaalarm.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseTextExtractorTest {

    @Test
    fun `prefers top-level output_text`() {
        val response = ResponsesResponse(
            outputText = "  Hello there  ",
            output = listOf(
                OutputItem(
                    type = "message",
                    content = listOf(ContentPart(text = "ignored"))
                )
            )
        )
        assertEquals("Hello there", ResponseTextExtractor.extract(response))
    }

    @Test
    fun `falls back to message content parts`() {
        val response = ResponsesResponse(
            output = listOf(
                OutputItem(type = "reasoning", content = listOf(ContentPart(text = "think"))),
                OutputItem(
                    type = "message",
                    content = listOf(
                        ContentPart(outputText = "Good morning."),
                        ContentPart(text = "How was the night?")
                    )
                )
            )
        )
        assertEquals(
            "Good morning.\nHow was the night?",
            ResponseTextExtractor.extract(response)
        )
    }

    @Test
    fun `empty or missing content returns null`() {
        assertNull(ResponseTextExtractor.extract(ResponsesResponse()))
        assertNull(ResponseTextExtractor.extract(ResponsesResponse(outputText = "   ")))
        assertNull(
            ResponseTextExtractor.extractFromOutput(
                listOf(OutputItem(type = "message", content = emptyList()))
            )
        )
    }
}
