package com.anaalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StreakCalculatorTest {

    private val today = LocalDate.of(2026, 8, 21)

    private fun event(name: String, date: LocalDate, done: Boolean = true) =
        HabitEventEntity(name = name, date = date.toString(), done = done)

    @Test
    fun `streak counts consecutive days ending today`() {
        val events = listOf(
            event("water", today),
            event("water", today.minusDays(1)),
            event("water", today.minusDays(2))
        )
        assertEquals(mapOf("water" to 3), StreakCalculator.compute(listOf("water"), events, today))
    }

    @Test
    fun `unmarked today still counts the streak through yesterday`() {
        val events = listOf(
            event("stretch", today.minusDays(1)),
            event("stretch", today.minusDays(2)),
            event("stretch", today.minusDays(3))
        )
        // Morning in progress: yesterday's chain is preserved.
        assertEquals(mapOf("stretch" to 3), StreakCalculator.compute(listOf("stretch"), events, today))
    }

    @Test
    fun `a gap breaks the streak`() {
        val events = listOf(
            event("water", today),
            event("water", today.minusDays(2))
        )
        val streaks = StreakCalculator.compute(listOf("water"), events, today)
        assertEquals(1, streaks["water"])
    }

    @Test
    fun `an explicitly unmarked day breaks the chain like a missing day`() {
        val events = listOf(
            event("water", today),
            event("water", today.minusDays(1), done = false),
            event("water", today.minusDays(2))
        )
        val streaks = StreakCalculator.compute(listOf("water"), events, today)
        assertEquals(1, streaks["water"])
    }

    @Test
    fun `habits without history are absent from the result`() {
        val streaks = StreakCalculator.compute(
            listOf("never-done"),
            emptyList(),
            today
        )
        assertTrue(streaks.isEmpty())
        assertFalse(streaks.containsKey("never-done"))
    }

    @Test
    fun `habits are independent of each other`() {
        val events = listOf(
            event("water", today),
            event("stretch", today.minusDays(1))
        )
        val streaks = StreakCalculator.compute(listOf("water", "stretch"), events, today)
        assertEquals(1, streaks["water"])
        assertEquals(1, streaks["stretch"])
    }

    @Test
    fun `unparseable dates are ignored instead of crashing`() {
        val events = listOf(
            HabitEventEntity(name = "water", date = "not-a-date", done = true),
            event("water", today)
        )
        assertEquals(mapOf("water" to 1), StreakCalculator.compute(listOf("water"), events, today))
    }
}
