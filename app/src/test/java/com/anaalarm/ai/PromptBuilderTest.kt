package com.anaalarm.ai

import com.anaalarm.data.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PromptBuilderTest {

    private val now = LocalDateTime.of(2026, 8, 1, 7, 15)

    @Test
    fun `includes name language habits interests and yesterday`() {
        val settings = AppSettings(
            name = "Alex",
            language = "en",
            habits = listOf("water plants"),
            interests = listOf("photography"),
            sessionMinutes = 12
        )
        val prompt = PromptBuilder.buildInstructions(settings, now, "2026-07-31: call mom")

        assertTrue(prompt.contains("Alex"))
        assertTrue(prompt.contains("English"))
        assertTrue(prompt.contains("water plants"))
        assertTrue(prompt.contains("photography"))
        assertTrue(prompt.contains("2026-07-31: call mom"))
        assertTrue(prompt.contains("12 minutes"))
        assertTrue(prompt.contains("Saturday, August 1, 2026"))
    }

    @Test
    fun `blank name falls back to there`() {
        val prompt = PromptBuilder.buildInstructions(AppSettings(), now, "")
        assertTrue(prompt.contains("companion of there"))
        assertTrue(prompt.contains("habits: none"))
        assertTrue(prompt.contains("previous day: none"))
    }

    @Test
    fun `portuguese language directive`() {
        val prompt = PromptBuilder.buildInstructions(
            AppSettings(language = "pt", name = "Ana"),
            now,
            ""
        )
        assertTrue(prompt.contains("Portuguese (Brazil)"))
        assertFalse(prompt.contains("Always reply in English"))
    }

    @Test
    fun `end prompt asks for farewell`() {
        val end = PromptBuilder.buildEndPrompt()
        assertTrue(end.contains("farewell") || end.contains("get up"))
    }
}
