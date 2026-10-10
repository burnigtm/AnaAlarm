package com.anaalarm.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {
    @Test fun `global commands are bilingual and punctuation tolerant`() {
        mapOf("Snooze!" to VoiceCommand.SNOOZE, "soneca" to VoiceCommand.SNOOZE,
            "repita isso" to VoiceCommand.REPEAT, "say that again" to VoiceCommand.REPEAT,
            "quero digitar" to VoiceCommand.TYPE, "switch to typing" to VoiceCommand.TYPE,
            "pode parar" to VoiceCommand.STOP, "I'm up" to VoiceCommand.STOP).forEach { (text, expected) ->
            assertEquals(text, expected, VoiceCommands.parse(text))
        }
    }
    @Test fun `ordinary sentences and negations do not execute commands`() {
        listOf("I want to repeat my workout", "please don't stop", "do not snooze", "não pode parar",
            "never say goodbye", "I need to get up early tomorrow", "I am not done", "stopwatch", "").forEach {
            assertNull(it, VoiceCommands.parse(it))
        }
    }
    @Test fun `routine completion takes precedence over legacy done`() {
        assertEquals(VoiceCommand.COMPLETE, VoiceCommands.parse("done", routine = true))
        assertEquals(VoiceCommand.STOP, VoiceCommands.parse("done"))
        listOf("completed", "mark done", "feito", "concluído", "terminei").forEach {
            assertEquals(VoiceCommand.COMPLETE, VoiceCommands.parse(it, routine = true))
        }
    }
    @Test fun `routine commands are unavailable outside their context`() {
        listOf("skip", "next", "pular", "próximo").forEach {
            assertNull(VoiceCommands.parse(it))
            assertEquals(VoiceCommand.SKIP, VoiceCommands.parse(it, routine = true))
        }
        assertNull(VoiceCommands.parse("cancel"))
        assertEquals(VoiceCommand.CANCEL, VoiceCommands.parse("cancelar", challenge = true))
    }
    @Test fun `normalization is locale independent and preserves apostrophes`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
            assertEquals(VoiceCommand.TYPE, VoiceCommands.parse("DIGITAR"))
            assertEquals(VoiceCommand.STOP, VoiceCommands.parse("I’m up"))
        } finally { java.util.Locale.setDefault(original) }
    }
}
