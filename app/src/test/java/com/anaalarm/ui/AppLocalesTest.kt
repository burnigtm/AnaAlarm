package com.anaalarm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocalesTest {

    @Test
    fun `language codes map onto BCP-47 tags`() {
        assertEquals("en", AppLocales.tagFor("en"))
        assertEquals("pt-BR", AppLocales.tagFor("pt"))
    }

    @Test
    fun `unknown language codes fall back to English`() {
        assertEquals("en", AppLocales.tagFor("xx"))
        assertEquals("en", AppLocales.tagFor(""))
    }
}
