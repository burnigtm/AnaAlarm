package com.anaalarm.debug

import com.anaalarm.AnaAlarmApp
import com.anaalarm.alarm.DismissalChallenges
import com.anaalarm.data.Pronouns
import com.anaalarm.data.Tones
import com.anaalarm.ui.avatar.Avatars
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Seeds a realistic weekday of AnaAlarm data so documentation screenshots show the product
 * in use rather than empty first-run chrome.
 */
internal object DocsDemoData {

    /** Monday–Friday; bit 0 is Sunday. */
    private const val WEEKDAYS = 0b0111110
    private const val WEEKEND = 0b1000001

    suspend fun seed(app: AnaAlarmApp, emptyHome: Boolean) {
        app.ensureCredentialStorage()
        // Avoid Room.clearAllTables() here: it races AlarmScheduler's application-scope
        // reconcile and can leave the gallery sitting on an empty first frame.

        app.settingsStore.update(
            name = "Maya",
            language = "en",
            habits = listOf("water the plants", "stretch", "take vitamins"),
            interests = listOf("photography", "running"),
            sessionMinutes = 8,
            snoozeMinutes = 10,
            pronouns = Pronouns.NEUTRAL,
            tone = Tones.UPBEAT,
            avatar = Avatars.CHEETAH
        )

        runCatching {
            app.memoryStore.alarms.first().forEach { alarm ->
                app.memoryStore.deleteAlarm(alarm.id)
            }
        }

        if (emptyHome) {
            app.memoryStore.saveDailyLog("")
            return
        }

        app.memoryStore.upsertAlarm(
            id = 1,
            hour = 7,
            minute = 0,
            days = WEEKDAYS,
            snoozeMinutes = 8,
            enabled = true,
            challengeType = DismissalChallenges.Type.MATH.code,
            maxSnoozes = 2
        )
        app.memoryStore.upsertAlarm(
            id = 2,
            hour = 9,
            minute = 30,
            days = WEEKEND,
            snoozeMinutes = 10,
            enabled = true,
            challengeType = DismissalChallenges.Type.MEMORY.code,
            maxSnoozes = 0
        )

        val today = LocalDate.now()
        app.memoryStore.saveDailyLog(
            "You slept well and promised to finish the design review before lunch. " +
                "Kiko already asked whether the plants got water."
        )
        for (offset in 0..6) {
            val day = today.minusDays(offset.toLong())
            app.memoryStore.setHabitDone("water the plants", day, true)
            if (offset <= 2) app.memoryStore.setHabitDone("stretch", day, true)
            if (offset in 1..4) app.memoryStore.setHabitDone("take vitamins", day, true)
        }

        val zone = ZoneId.systemDefault()
        for (offset in 0..4) {
            val morning = LocalDateTime.of(today.minusDays(offset.toLong()), LocalTime.of(7, 4))
            val start = morning.atZone(zone).toInstant().toEpochMilli()
            val end = start + 8L * 60_000
            app.memoryStore.recordSession(start, end, turns = 6)
            app.memoryStore.recordUsage(
                date = today.minusDays(offset.toLong()),
                sessionId = start,
                inputTokens = 1_800,
                outputTokens = 620,
                cachedTokens = 240,
                totalTokens = 2_660
            )
        }
    }
}
