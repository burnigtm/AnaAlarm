package com.anaalarm.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val snoozeMinutes: Int = 10,
    val enabled: Boolean = true,
    /** One of [com.anaalarm.alarm.DismissalChallenges.Type]; proof required before Stop works. */
    val challengeType: Int = 0,
    /** Maximum snoozes per firing; 0 means unlimited. */
    val maxSnoozes: Int = 0,
    /** Custom alarm sound; null uses the system default alarm sound. */
    val ringtoneUri: String? = null
) {
    val timeMinutes: Int get() = hour * 60 + minute
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["sessionId", "timestamp"]),
        Index(value = ["timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val timestamp: Long
)

@Entity(
    tableName = "daily_logs",
    indices = [Index(value = ["date"], unique = true)]
)
data class DailyLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val summary: String
)

/** One successful model call's token accounting, for the in-app usage/cost dashboard. */
@Entity(
    tableName = "usage",
    indices = [Index(value = ["date"])]
)
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO-8601 local date of the request; aggregation key for today/7-day views. */
    val date: String,
    /** Session the call belonged to; null only for calls outside any session. */
    val sessionId: Long?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val totalTokens: Long,
    val timestamp: Long
)

/** One completed wake-up session's shape, for future statistics surfaces. */
@Entity(tableName = "session_records")
data class SessionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock milliseconds, aligned with [MessageEntity.timestamp]. */
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val turns: Int
)

/** One habit's done/not-done mark for one day; at most one row per (name, date). */
@Entity(
    tableName = "habit_events",
    indices = [Index(value = ["name", "date"], unique = true)]
)
data class HabitEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val date: String,
    val done: Boolean
)
