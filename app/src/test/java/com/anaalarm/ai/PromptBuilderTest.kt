package com.anaalarm.ai

import com.anaalarm.data.AppSettings
import com.anaalarm.data.Pronouns
import com.anaalarm.data.Tones
import org.junit.Assert.assertEquals
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

    @Test
    fun `default persona uses neutral pronouns`() {
        val prompt = PromptBuilder.buildInstructions(AppSettings(name = "Sam"), now, "")

        assertTrue(prompt.contains("their habits"))
        assertTrue(prompt.contains("their interests"))
        assertTrue(prompt.contains("If they promised something"))
        assertTrue(prompt.contains("says they are awake"))
        assertFalse(prompt.contains("her habits"))
        assertFalse(prompt.contains("she is awake"))
    }

    @Test
    fun `she persona uses feminine forms`() {
        val prompt = PromptBuilder.buildInstructions(
            AppSettings(name = "Marina", pronouns = Pronouns.SHE),
            now,
            ""
        )

        assertTrue(prompt.contains("her habits"))
        assertTrue(prompt.contains("her interests"))
        assertTrue(prompt.contains("If she promised something"))
        assertTrue(prompt.contains("says she is awake"))
    }

    @Test
    fun `he persona uses masculine forms`() {
        val prompt = PromptBuilder.buildInstructions(
            AppSettings(name = "Tom", pronouns = Pronouns.HE),
            now,
            ""
        )

        assertTrue(prompt.contains("his habits"))
        assertTrue(prompt.contains("his interests"))
        assertTrue(prompt.contains("If he promised something"))
        assertTrue(prompt.contains("says he is awake"))
    }

    @Test
    fun `tone directives differ per selection`() {
        val gentle = PromptBuilder.toneDirective(Tones.GENTLE)
        val upbeat = PromptBuilder.toneDirective(Tones.UPBEAT)
        val drill = PromptBuilder.toneDirective(Tones.DRILL)

        assertTrue(gentle.contains("soft, calm"))
        assertTrue(upbeat.contains("cheerful and energetic"))
        assertTrue(drill.contains("drill-sergeant"))
        // All three stay warm: the drill tone must never become genuinely hostile.
        assertTrue(drill.contains("stay warm"))
    }

    @Test
    fun `unknown tone falls back to the default upbeat directive`() {
        assertEquals(
            PromptBuilder.toneDirective(Tones.UPBEAT),
            PromptBuilder.toneDirective("something-else")
        )
    }

    @Test
    fun `instructions embed the selected tone directive`() {
        val prompt = PromptBuilder.buildInstructions(
            AppSettings(name = "Sam", tone = Tones.DRILL),
            now,
            ""
        )
        assertTrue(prompt.contains("drill-sergeant"))
    }

    @Test
    fun `default instructions use the upbeat tone`() {
        val prompt = PromptBuilder.buildInstructions(AppSettings(name = "Sam"), now, "")
        assertTrue(prompt.contains("cheerful and energetic"))
        assertFalse(prompt.contains("drill-sergeant"))
    }

    @Test
    fun `streak line renders habits longest first and stays kind`() {
        val line = PromptBuilder.streakLine(
            mapOf("water plants" to 3, "stretch" to 9)
        )
        assertTrue(line.contains("stretch: 9-day streak"))
        assertTrue(line.contains("water plants: 3-day streak"))
        assertTrue(line.indexOf("stretch") < line.indexOf("water plants"))
        assertTrue(line.contains("never shame"))
    }

    @Test
    fun `empty streak map yields a blank placeholder line`() {
        assertEquals("", PromptBuilder.streakLine(emptyMap()))
    }

    @Test
    fun `instructions embed active streaks when provided`() {
        val prompt = PromptBuilder.buildInstructions(
            AppSettings(name = "Sam"),
            now,
            "",
            habitStreaks = mapOf("water plants" to 5)
        )
        assertTrue(prompt.contains("water plants: 5-day streak"))
    }

    @Test
    fun `buddy line mentions the selected species`() {
        assertTrue(PromptBuilder.buddyLine("cheetah").contains("Kiko the cheetah"))
        assertTrue(PromptBuilder.buddyLine("dino").contains("Dax the dino"))
        assertTrue(PromptBuilder.buddyLine("zebra").contains("Zuri the zebra"))
    }

    @Test
    fun `unknown avatar falls back to the cheetah buddy line`() {
        assertEquals(PromptBuilder.buddyLine("cheetah"), PromptBuilder.buddyLine("dragon"))
        // Avatars.from is case-sensitive and trims nothing: padding and case fall back too.
        assertEquals(PromptBuilder.buddyLine("cheetah"), PromptBuilder.buddyLine("CHEETAH"))
        assertEquals(PromptBuilder.buddyLine("cheetah"), PromptBuilder.buddyLine("cheetah "))
    }

    @Test
    fun `buddy caps itself to one mention per session`() {
        assertTrue(PromptBuilder.buddyLine("dino").contains("at most once per session"))
    }

    @Test
    fun `instructions embed the wake-up buddy`() {
        val prompt = PromptBuilder.buildInstructions(
            AppSettings(name = "Sam", avatar = "zebra"),
            now,
            ""
        )
        assertTrue(prompt.contains("Zuri the zebra, drawn on screen next to you"))
    }
}
