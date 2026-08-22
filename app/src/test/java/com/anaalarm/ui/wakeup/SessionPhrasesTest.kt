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

    @Test
    fun `short tokens inside longer sentences do not end the session`() {
        assertFalse(SessionPhrases.isStopPhrase("vou para o trabalho"))
        assertFalse(SessionPhrases.isStopPhrase("I haven't done my stretches"))
        assertFalse(SessionPhrases.isStopPhrase("I have not done that yet"))
        assertFalse(SessionPhrases.isStopPhrase("please don't stop talking"))
        assertFalse(SessionPhrases.isStopPhrase("I can't stop thinking about today"))
        assertFalse(SessionPhrases.isStopPhrase("I don't want to get up"))
        assertFalse(SessionPhrases.isStopPhrase("get up later maybe"))
        assertFalse(SessionPhrases.isStopPhrase("say bye to mom"))
        assertFalse(SessionPhrases.isStopPhrase("chega de café"))
    }

    @Test
    fun `punctuation around a standalone command still matches`() {
        assertTrue(SessionPhrases.isStopPhrase("Stop!"))
        assertTrue(SessionPhrases.isStopPhrase("   STOP   "))
        assertTrue(SessionPhrases.isStopPhrase("para."))
        assertTrue(SessionPhrases.isStopPhrase("done"))
        assertTrue(SessionPhrases.isStopPhrase("get up"))
    }

    @Test
    fun `longer wake phrases still match with surrounding words`() {
        assertTrue(SessionPhrases.isStopPhrase("ok, time to get up"))
        assertTrue(SessionPhrases.isStopPhrase("Goodbye Ana"))
        assertTrue(SessionPhrases.isStopPhrase("já acordei"))
        assertTrue(SessionPhrases.isStopPhrase("pode parar agora"))
    }
}
