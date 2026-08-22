package com.anaalarm.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

data class ChatMessage(val role: String, val content: String)

class MemoryStore(
    private val db: AnaDatabase,
    private val settingsStore: SettingsStore
) {
    private val alarmDao = db.alarmDao()
    private val messageDao = db.messageDao()
    private val dailyLogDao = db.dailyLogDao()
    private val usageDao = db.usageDao()
    private val sessionRecordDao = db.sessionRecordDao()
    private val habitEventDao = db.habitEventDao()
    private val dailyLogWriteMutex = Mutex()
    private val lastSessionId = AtomicLong(0L)

    val alarms: Flow<List<AlarmEntity>> = alarmDao.observeAll()

    suspend fun getAlarm(id: Long): AlarmEntity? = alarmDao.getById(id)

    suspend fun upsertAlarm(
        id: Long,
        hour: Int,
        minute: Int,
        days: Int,
        snoozeMinutes: Int,
        enabled: Boolean,
        challengeType: Int = 0,
        maxSnoozes: Int = 0,
        ringtoneUri: String? = null
    ): Long {
        val alarm = AlarmEntity(
            id = id,
            hour = hour,
            minute = minute,
            days = days,
            snoozeMinutes = snoozeMinutes,
            enabled = enabled,
            challengeType = challengeType,
            maxSnoozes = maxSnoozes,
            ringtoneUri = ringtoneUri
        )
        return alarmDao.upsert(alarm)
    }

    suspend fun deleteAlarm(id: Long) {
        alarmDao.getById(id)?.let { alarmDao.delete(it) }
    }

    suspend fun setAlarmEnabled(id: Long, enabled: Boolean) = alarmDao.setEnabled(id, enabled)

    suspend fun getEnabledAlarms(): List<AlarmEntity> = alarmDao.getEnabled()

    suspend fun beginSession(): Long = lastSessionId.updateAndGet { previous ->
        maxOf(System.currentTimeMillis(), previous + 1L)
    }

    suspend fun addMessage(sessionId: Long, role: String, content: String) {
        messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                role = role,
                content = content,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun getSessionHistory(sessionId: Long, limit: Int = 20): List<ChatMessage> {
        val messages = messageDao.getLastMessages(sessionId, limit)
        return messages.reversed().map { ChatMessage(it.role, it.content) }
    }

    suspend fun clearSession(sessionId: Long) = messageDao.clearSession(sessionId)

    suspend fun pruneMessagesOlderThan(cutoffTimestamp: Long): Int =
        messageDao.deleteOlderThan(cutoffTimestamp)

    suspend fun todaySummary(): String =
        dailyLogDao.getByDate(LocalDate.now().toString())?.summary ?: ""

    suspend fun yesterdaySummary(): String {
        val yesterday = dailyLogDao.getLatestBefore(LocalDate.now().toString()) ?: return ""
        val date = runCatching { LocalDate.parse(yesterday.date, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        val dayLabel = date?.toString() ?: yesterday.date
        return if (yesterday.summary.isBlank()) "" else "$dayLabel: ${yesterday.summary}"
    }

    suspend fun saveDailyLog(summary: String, date: LocalDate = LocalDate.now()) {
        val dateKey = date.toString()
        val existing = dailyLogDao.getByDate(dateKey)
        dailyLogDao.upsert(
            DailyLogEntity(
                id = existing?.id ?: 0,
                date = dateKey,
                summary = summary
            )
        )
    }

    /**
     * Adds one completed session to the day's durable memory without erasing an earlier session.
     * Writes are serialized because two alarm sessions can finish close together.
     */
    suspend fun appendDailyLog(summary: String, date: LocalDate = LocalDate.now()) {
        val addition = summary.trim()
        if (addition.isEmpty()) return
        dailyLogWriteMutex.withLock {
            val dateKey = date.toString()
            val existing = dailyLogDao.getByDate(dateKey)
            val combined = listOfNotNull(
                existing?.summary?.trim()?.takeIf { it.isNotEmpty() },
                addition
            ).joinToString("\n").takeLast(MAX_DAILY_LOG_CHARS)
            dailyLogDao.upsert(
                DailyLogEntity(
                    id = existing?.id ?: 0,
                    date = dateKey,
                    summary = combined
                )
            )
        }
    }

    private companion object {
        // ConversationEngine contributes at most 900 chars per session; retain two full sessions
        // while keeping the next-day prompt bounded for latency and cost.
        const val MAX_DAILY_LOG_CHARS = 1_801
    }

    /**
     * Records one successful model call's token accounting. Rows with no provider-reported
     * usage are skipped by the caller; zero-token reports are still stored so request counts
     * stay truthful.
     */
    suspend fun recordUsage(
        date: LocalDate,
        sessionId: Long?,
        inputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long,
        totalTokens: Long
    ) {
        usageDao.insert(
            UsageEntity(
                date = date.toString(),
                sessionId = sessionId,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedTokens = cachedTokens,
                totalTokens = totalTokens,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /** Aggregated token totals for the last [daysBack] days, inclusive of today. */
    suspend fun usageSummary(daysBack: Int): TokenUsageSummary =
        usageDao.summarySince(LocalDate.now().minusDays(daysBack.toLong()).toString())

    /** Persists one completed session's shape; degenerate records are ignored. */
    suspend fun recordSession(startedAtMillis: Long, endedAtMillis: Long, turns: Int) {
        if (turns <= 0 && endedAtMillis <= startedAtMillis) return
        sessionRecordDao.insert(
            SessionRecordEntity(
                startedAt = startedAtMillis,
                endedAt = endedAtMillis,
                durationMs = (endedAtMillis - startedAtMillis).coerceAtLeast(0L),
                turns = turns
            )
        )
    }

    suspend fun recentSessionRecords(limit: Int = 30): List<SessionRecordEntity> =
        sessionRecordDao.getRecent(limit)

    /** Marks (or unmarks) one habit for [date]; the unique index keeps a single row per day. */
    suspend fun setHabitDone(name: String, date: LocalDate, done: Boolean) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        habitEventDao.upsert(
            HabitEventEntity(name = trimmed, date = date.toString(), done = done)
        )
    }

    suspend fun habitsForDate(date: LocalDate): List<HabitEventEntity> =
        habitEventDao.getByDate(date.toString())

    suspend fun recentHabitEvents(daysBack: Int, today: LocalDate = LocalDate.now()): List<HabitEventEntity> =
        habitEventDao.getSince(today.minusDays(daysBack.toLong()).toString())

    /** Current streaks for the given habit names; habits without history are absent. */
    suspend fun habitStreaks(
        habitNames: List<String>,
        today: LocalDate = LocalDate.now()
    ): Map<String, Int> = StreakCalculator.compute(
        habitNames = habitNames,
        events = recentHabitEvents(daysBack = 60, today = today),
        today = today
    )

    /** Bounds the habit ledger; marks older than two months stop counting toward streaks. */
    suspend fun pruneHabitEvents(today: LocalDate = LocalDate.now()): Int =
        habitEventDao.deleteBefore(today.minusDays(60).toString())

    /** Full daily-log history for encrypted export, oldest first. */
    suspend fun exportableDailyLogs(): List<DailyLogEntity> = dailyLogDao.getAll()
}
