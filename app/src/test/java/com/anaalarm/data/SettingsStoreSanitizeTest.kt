package com.anaalarm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreSanitizeTest {

    @Test
    fun `sanitizeSecret trims whitespace and bom`() {
        assertEquals("sk-abc", SettingsStore.sanitizeSecret("  sk-abc \n"))
        assertEquals("sk-abc", SettingsStore.sanitizeSecret("\uFEFFsk-abc"))
        assertEquals("sk-abc", SettingsStore.sanitizeSecret("\uFEFF  sk-abc  "))
    }
}
