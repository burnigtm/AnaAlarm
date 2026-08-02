package com.anaalarm.ui.wakeup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phrases that end a session. These are matched against raw speech-recognition output, so
 * casing, padding and surrounding words all have to work.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class SessionPhrasesInstrumentedTest {

    @Test
    fun englishWakeUpPhrasesEndTheSession() {
        listOf(
            "stop",
            "Stop!",
            "I'm up",
            "im up",
            "I am up",
            "ok, time to get up",
            "done",
            "bye",
            "Goodbye Ana"
        ).forEach { phrase ->
            assertTrue("'$phrase' should end the session", SessionPhrases.isStopPhrase(phrase))
        }
    }

    @Test
    fun portugueseWakeUpPhrasesEndTheSession() {
        listOf(
            "para",
            "chega",
            "já acordei",
            "levantei",
            "estou de pé",
            "pode parar"
        ).forEach { phrase ->
            assertTrue("'$phrase' should end the session", SessionPhrases.isStopPhrase(phrase))
        }
    }

    @Test
    fun ordinaryConversationKeepsTheSessionRunning() {
        listOf(
            "",
            "   ",
            "good morning",
            "I slept well",
            "eu dormi bem",
            "what is the plan for today"
        ).forEach { phrase ->
            assertFalse("'$phrase' should not end the session", SessionPhrases.isStopPhrase(phrase))
        }
    }

    @Test
    fun matchingIsCaseInsensitiveAndIgnoresPadding() {
        assertTrue(SessionPhrases.isStopPhrase("   STOP   "))
        assertTrue(SessionPhrases.isStopPhrase("BYE"))
    }

    @Test
    fun everyConfiguredPhraseMatchesItself() {
        SessionPhrases.stopPhrases.forEach { phrase ->
            assertTrue(phrase, SessionPhrases.isStopPhrase(phrase))
        }
    }
}
