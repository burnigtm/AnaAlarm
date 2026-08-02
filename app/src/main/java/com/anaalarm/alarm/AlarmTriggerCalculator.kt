package com.anaalarm.alarm

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Pure next-fire-time calculation for alarms.
 * [days] bitmask: bit 0 = Sunday … bit 6 = Saturday. Zero means every day (one-shot re-arms daily).
 */
object AlarmTriggerCalculator {

    fun nextTriggerTime(hour: Int, minute: Int, days: Int, now: LocalDateTime): LocalDateTime {
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }

        var candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)

        val repeat = (0..6).filter { (days and (1 shl it)) != 0 }
        if (repeat.isEmpty()) return candidate

        var offset = 0L
        while (offset < 7) {
            val check = candidate.plusDays(offset)
            if (dayBit(check.dayOfWeek) in repeat) return check
            offset++
        }
        return candidate
    }

    fun dayBit(day: DayOfWeek): Int = when (day) {
        DayOfWeek.SUNDAY -> 0
        DayOfWeek.MONDAY -> 1
        DayOfWeek.TUESDAY -> 2
        DayOfWeek.WEDNESDAY -> 3
        DayOfWeek.THURSDAY -> 4
        DayOfWeek.FRIDAY -> 5
        DayOfWeek.SATURDAY -> 6
    }

    /** Bitmask helpers for UI / tests. */
    fun bitFor(day: DayOfWeek): Int = 1 shl dayBit(day)

    fun hasDay(days: Int, day: DayOfWeek): Boolean = (days and bitFor(day)) != 0
}
