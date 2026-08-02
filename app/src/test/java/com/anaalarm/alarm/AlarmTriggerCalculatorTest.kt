package com.anaalarm.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class AlarmTriggerCalculatorTest {

    @Test
    fun `same-day future time with no repeat days fires today`() {
        val now = LocalDateTime.of(2026, 8, 1, 7, 0) // Saturday
        val next = AlarmTriggerCalculator.nextTriggerTime(8, 30, days = 0, now = now)
        assertEquals(LocalDateTime.of(2026, 8, 1, 8, 30), next)
    }

    @Test
    fun `past time today rolls to tomorrow when no repeat days`() {
        val now = LocalDateTime.of(2026, 8, 1, 9, 0)
        val next = AlarmTriggerCalculator.nextTriggerTime(8, 30, days = 0, now = now)
        assertEquals(LocalDateTime.of(2026, 8, 2, 8, 30), next)
    }

    @Test
    fun `exact current minute is treated as past and rolls forward`() {
        val now = LocalDateTime.of(2026, 8, 1, 8, 30, 0)
        val next = AlarmTriggerCalculator.nextTriggerTime(8, 30, days = 0, now = now)
        assertEquals(LocalDateTime.of(2026, 8, 2, 8, 30), next)
    }

    @Test
    fun `weekday repeat skips non-matching days`() {
        // Saturday 2026-08-01; Mon/Wed bits = bit1 | bit3 = 2+8 = 10
        val now = LocalDateTime.of(2026, 8, 1, 7, 0)
        val days = AlarmTriggerCalculator.bitFor(DayOfWeek.MONDAY) or
            AlarmTriggerCalculator.bitFor(DayOfWeek.WEDNESDAY)
        val next = AlarmTriggerCalculator.nextTriggerTime(7, 30, days, now)
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 8, 3, 7, 30), next)
    }

    @Test
    fun `sunday bit zero schedules sunday`() {
        val now = LocalDateTime.of(2026, 8, 1, 10, 0) // Saturday
        val days = AlarmTriggerCalculator.bitFor(DayOfWeek.SUNDAY)
        val next = AlarmTriggerCalculator.nextTriggerTime(9, 0, days, now)
        assertEquals(DayOfWeek.SUNDAY, next.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 8, 2, 9, 0), next)
    }

    @Test
    fun `matching day later today is chosen`() {
        val now = LocalDateTime.of(2026, 8, 3, 6, 0) // Monday
        val days = AlarmTriggerCalculator.bitFor(DayOfWeek.MONDAY)
        val next = AlarmTriggerCalculator.nextTriggerTime(7, 0, days, now)
        assertEquals(LocalDateTime.of(2026, 8, 3, 7, 0), next)
    }

    @Test
    fun `matching day after time passed goes to next week`() {
        val now = LocalDateTime.of(2026, 8, 3, 8, 0) // Monday
        val days = AlarmTriggerCalculator.bitFor(DayOfWeek.MONDAY)
        val next = AlarmTriggerCalculator.nextTriggerTime(7, 0, days, now)
        assertEquals(LocalDateTime.of(2026, 8, 10, 7, 0), next)
    }

    @Test
    fun `dayBit mapping matches documented sunday-zero convention`() {
        assertEquals(0, AlarmTriggerCalculator.dayBit(DayOfWeek.SUNDAY))
        assertEquals(1, AlarmTriggerCalculator.dayBit(DayOfWeek.MONDAY))
        assertEquals(6, AlarmTriggerCalculator.dayBit(DayOfWeek.SATURDAY))
        assertTrue(AlarmTriggerCalculator.hasDay(1, DayOfWeek.SUNDAY))
        assertFalse(AlarmTriggerCalculator.hasDay(1, DayOfWeek.MONDAY))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid hour is rejected`() {
        AlarmTriggerCalculator.nextTriggerTime(24, 0, 0, LocalDateTime.of(2026, 8, 1, 0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid minute is rejected`() {
        AlarmTriggerCalculator.nextTriggerTime(10, 60, 0, LocalDateTime.of(2026, 8, 1, 0, 0))
    }
}
