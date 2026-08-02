package com.anaalarm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsListsTest {

    @Test
    fun `join trims and drops blanks`() {
        assertEquals(
            "water plants,make coffee",
            SettingsLists.join(listOf(" water plants ", "", "make coffee", "  "))
        )
    }

    @Test
    fun `split handles null blank and spaces`() {
        assertEquals(emptyList<String>(), SettingsLists.split(null))
        assertEquals(emptyList<String>(), SettingsLists.split(""))
        assertEquals(
            listOf("water plants", "make coffee"),
            SettingsLists.split(" water plants , make coffee , ")
        )
    }

    @Test
    fun `round trip preserves items`() {
        val items = listOf("a", "b", "c")
        assertEquals(items, SettingsLists.split(SettingsLists.join(items)))
    }
}
