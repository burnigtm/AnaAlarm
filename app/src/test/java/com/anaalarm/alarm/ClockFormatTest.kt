package com.anaalarm.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ClockFormatTest {

    @Test
    fun `time pattern follows the 12 or 24 hour flag`() {
        assertEquals("HH:mm", ClockFormat.timePattern(is24Hour = true))
        assertEquals("h:mm a", ClockFormat.timePattern(is24Hour = false))
    }

    @Test
    fun `formatTime renders the same instant in both clocks`() {
        val locale = Locale.US
        assertEquals("15:05", ClockFormat.formatTime(15, 5, is24Hour = true, locale))
        val twelve = ClockFormat.formatTime(15, 5, is24Hour = false, locale)
        assertTrue(twelve.contains("3:05"))
        assertTrue(twelve.contains("PM"))
        assertEquals(
            "07:00",
            ClockFormat.formatTime(7, 0, is24Hour = true, locale)
        )
    }

    @Test
    fun `widget pattern includes the weekday and the same hour cycle`() {
        assertEquals("EEE HH:mm", ClockFormat.widgetPattern(true))
        assertEquals("EEE h:mm a", ClockFormat.widgetPattern(false))
        val when_ = ZonedDateTime.of(
            LocalDate.of(2026, 8, 3),
            LocalTime.of(15, 5),
            ZoneId.of("UTC")
        )
        val text = ClockFormat.formatZoned(when_, is24Hour = false, locale = Locale.US)
        assertTrue(text.contains("3:05"))
        assertTrue(text.contains("PM"))
    }
}
