package com.anaalarm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PronounsTest {

    @Test
    fun `neutral is the default and maps to they-forms`() {
        assertEquals(Pronouns.NEUTRAL, AppSettings().pronouns)
        val forms = Pronouns.forms(Pronouns.NEUTRAL)
        assertEquals("they", forms.subject)
        assertEquals("their", forms.possessive)
        assertEquals("are", forms.beVerb)
    }

    @Test
    fun `she maps to feminine forms`() {
        val forms = Pronouns.forms(Pronouns.SHE)
        assertEquals("she", forms.subject)
        assertEquals("her", forms.possessive)
        assertEquals("is", forms.beVerb)
    }

    @Test
    fun `he maps to masculine forms`() {
        val forms = Pronouns.forms(Pronouns.HE)
        assertEquals("he", forms.subject)
        assertEquals("his", forms.possessive)
        assertEquals("is", forms.beVerb)
    }

    @Test
    fun `unknown values fall back to neutral`() {
        val forms = Pronouns.forms("something-else")
        assertEquals("they", forms.subject)
        assertEquals("their", forms.possessive)
    }

    @Test
    fun `value list covers every selectable option`() {
        assertEquals(listOf(Pronouns.NEUTRAL, Pronouns.SHE, Pronouns.HE), Pronouns.VALUES)
    }
}
