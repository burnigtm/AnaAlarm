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
        enabled: Boolean
    ): Long {
        val alarm = AlarmEntity(
            id = id,
            hour = hour,
            minute = minute,
            days = days,
            snoozeMinutes = snoozeMinutes,
            enabled = enabled
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
}
