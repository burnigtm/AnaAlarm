package com.anaalarm.ai

import com.anaalarm.data.ChatMessage
import com.anaalarm.data.MorningLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorningMemoryTest {

    @Test
    fun `extracts up to three distinct plans and skips the greeting prompt`() {
        val items = MorningMemory.extract(
            listOf(
                ChatMessage("user", MorningMemory.GREETING_PROMPT),
                ChatMessage("assistant", "What will you do today?"),
                ChatMessage("user", "I slept well. I'll call mom today."),
                ChatMessage("user", "I will call mom today"),
                ChatMessage("user", "I need to water the plants"),
                ChatMessage("user", "Vou caminhar"),
                ChatMessage("user", "I want to read a chapter")
            )
        )

        assertEquals(
            listOf("call mom today", "water the plants", "caminhar"),
            items
        )
    }

    @Test
    fun `portuguese plans are kept`() {
        assertEquals(
            listOf("ligar para a mãe", "regar as plantas"),
            MorningMemory.extract(
                listOf(
                    ChatMessage("user", "Hoje eu vou ligar para a mãe. Preciso regar as plantas.")
                )
            )
        )
    }

    @Test
    fun `transcript drops the synthetic greeting and labels speakers`() {
        val transcript = MorningMemory.formatTranscript(
            listOf(
                ChatMessage("user", MorningMemory.GREETING_PROMPT),
                ChatMessage("assistant", "Good morning!"),
                ChatMessage("user", "I will call mom")
            )
        )

        assertEquals("Ana: Good morning!\nYou: I will call mom", transcript)
        assertFalse(transcript.contains("Please begin"))
    }

    @Test
    fun `pack keeps items beside the raw summary and unpack splits them`() {
        val stored = MorningLog.pack("user: hi | assistant: bye", listOf("call mom", "walk"))
        val recap = MorningLog.unpack(stored)

        assertEquals("user: hi | assistant: bye", recap.summary)
        assertEquals(listOf("call mom", "walk"), recap.items)
        assertFalse(recap.summary.contains("call mom"))
    }

    @Test
    fun `merge keeps the newest three distinct items`() {
        assertEquals(
            listOf("B", "c", "d"),
            MorningLog.mergeItems(listOf("a", "b"), listOf("B", "c", "d"))
        )
    }

    @Test
    fun `plain logs without a marker stay a summary`() {
        val recap = MorningLog.unpack("today plan")
        assertEquals("today plan", recap.summary)
        assertTrue(recap.items.isEmpty())
    }
}
