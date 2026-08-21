package com.anaalarm.data

import java.time.LocalDate

/**
 * Current-streak computation for the recap card and the wake-up prompt.
 *
 * A streak counts consecutive marked days ending today (when today is already marked) or
 * yesterday (when it is not yet — the morning is still in progress). Unmarking a day breaks
 * the chain exactly like a missing day.
 */
object StreakCalculator {

    fun compute(
        habitNames: List<String>,
        events: List<HabitEventEntity>,
        today: LocalDate
    ): Map<String, Int> {
        val doneByHabit = events.asSequence()
            .filter { it.done }
            .groupBy { it.name }
            .mapValues { (_, rows) ->
                rows.mapNotNull { row ->
                    runCatching { LocalDate.parse(row.date) }.getOrNull()
                }.toSet()
            }
        return habitNames.associateWith { name -> streakFor(doneByHabit[name].orEmpty(), today) }
            .filterValues { it > 0 }
    }

    private fun streakFor(doneDays: Set<LocalDate>, today: LocalDate): Int {
        var cursor = if (today in doneDays) today else today.minusDays(1)
        var streak = 0
        while (cursor in doneDays) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }
}
