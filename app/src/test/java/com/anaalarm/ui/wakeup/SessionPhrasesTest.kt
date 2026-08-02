package com.anaalarm.ui.wakeup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPhrasesTest {

    @Test
    fun `english stop phrases are detected`() {
        assertTrue(SessionPhrases.isStopPhrase("stop"))
        assertTrue(SessionPhrases.isStopPhrase("I'm up"))
        assertTrue(SessionPhrases.isStopPhrase("im up now"))
        assertTrue(SessionPhrases.isStopPhrase("I am up"))
        assertTrue(SessionPhrases.isStopPhrase("Okay goodbye"))
        assertTrue(SessionPhrases.isStopPhrase("time to get up"))
    }

    @Test
    fun `portuguese stop phrases are detected`() {
        assertTrue(SessionPhrases.isStopPhrase("para"))
        assertTrue(SessionPhrases.isStopPhrase("chega"))
        assertTrue(SessionPhrases.isStopPhrase("acordei"))
        assertTrue(SessionPhrases.isStopPhrase("levantei"))
        assertTrue(SessionPhrases.isStopPhrase("estou de pé"))
        assertTrue(SessionPhrases.isStopPhrase("pode parar"))
    }

    @Test
    fun `normal conversation is not a stop phrase`() {
        assertFalse(SessionPhrases.isStopPhrase(""))
        assertFalse(SessionPhrases.isStopPhrase("   "))
        assertFalse(SessionPhrases.isStopPhrase("good morning Ana"))
        assertFalse(SessionPhrases.isStopPhrase("I slept well"))
        assertFalse(SessionPhrases.isStopPhrase("vou regar as plantas"))
    }
}
