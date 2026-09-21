package com.anaalarm.alarm

import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared 12/24-hour patterns. Callers pass [android.text.format.DateFormat.is24HourFormat]
 * so Home, the wake clock, and the next-alarm widget follow the same system preference
 * the alarm editor already uses.
 */
object ClockFormat {

    fun timePattern(is24Hour: Boolean): String = if (is24Hour) "HH:mm" else "h:mm a"

    fun widgetPattern(is24Hour: Boolean): String = if (is24Hour) "EEE HH:mm" else "EEE h:mm a"

    fun formatTime(
        hour: Int,
        minute: Int,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault()
    ): String = LocalTime.of(hour, minute).format(
        DateTimeFormatter.ofPattern(timePattern(is24Hour), locale)
    )

    fun formatZoned(
        time: ZonedDateTime,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault()
    ): String = time.format(DateTimeFormatter.ofPattern(widgetPattern(is24Hour), locale))
}
