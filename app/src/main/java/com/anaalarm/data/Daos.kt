package com.anaalarm.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyPriorityDao {
    @Query("SELECT * FROM daily_priorities WHERE date = :date")
    suspend fun get(date: String): DailyPriorityEntity?
    @Query("SELECT * FROM daily_priorities ORDER BY date")
    fun observeAll(): Flow<List<DailyPriorityEntity>>
    @Query("SELECT * FROM daily_priorities ORDER BY date")
    suspend fun exportAll(): List<DailyPriorityEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(priority: DailyPriorityEntity)
}

@Dao
interface CompanionMemoryDao {
    @Query("SELECT * FROM companion_memories ORDER BY updatedAt DESC")
    suspend fun exportAll(): List<CompanionMemoryEntity>
    @Query("SELECT * FROM companion_memories WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CompanionMemoryEntity>>

    @Query("SELECT * FROM companion_memories WHERE deleted = 0 ORDER BY updatedAt DESC LIMIT 20")
    suspend fun promptMemories(): List<CompanionMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(memory: CompanionMemoryEntity): Long

    @Query("UPDATE companion_memories SET content = :content, inferred = 0, updatedAt = :now WHERE id = :id AND deleted = 0")
    suspend fun edit(id: Long, content: String, now: Long)

    @Query("UPDATE companion_memories SET deleted = 1 WHERE id = :id")
    suspend fun forget(id: Long)
}

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getEnabled(): List<AlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: AlarmEntity): Long

    @Delete
    suspend fun delete(alarm: AlarmEntity)

    @Query("UPDATE alarms SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getSessionMessages(sessionId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getLastMessages(sessionId: Long, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: Long)

    @Query("DELETE FROM messages WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int
}

@Dao
interface DailyLogDao {
    @Query("SELECT * FROM daily_logs ORDER BY date DESC")
    fun observeAll(): Flow<List<DailyLogEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: DailyLogEntity)

    @Query("SELECT * FROM daily_logs WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): DailyLogEntity?

    @Query("SELECT * FROM daily_logs WHERE date < :date ORDER BY date DESC LIMIT 1")
    suspend fun getLatestBefore(date: String): DailyLogEntity?

    @Query("SELECT * FROM daily_logs ORDER BY date ASC")
    suspend fun getAll(): List<DailyLogEntity>
}

/** Aggregated token totals for a date range; column aliases match the property names. */
data class TokenUsageSummary(
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val totalTokens: Long,
    val requests: Int
)

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(usage: UsageEntity)

    @Query(
        """
        SELECT COALESCE(SUM(inputTokens), 0) AS inputTokens,
               COALESCE(SUM(outputTokens), 0) AS outputTokens,
               COALESCE(SUM(cachedTokens), 0) AS cachedTokens,
               COALESCE(SUM(totalTokens), 0) AS totalTokens,
               COUNT(*) AS requests
        FROM usage
        WHERE date >= :fromDate
        """
    )
    suspend fun summarySince(fromDate: String): TokenUsageSummary

    @Query("DELETE FROM usage WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int
}

@Dao
interface SessionRecordDao {
    @Insert
    suspend fun insert(record: SessionRecordEntity)

    @Query(
        """
        SELECT COUNT(*) FROM session_records
        WHERE startedAt = :startedAt AND endedAt = :endedAt AND turns = :turns
        """
    )
    suspend fun countMatching(startedAt: Long, endedAt: Long, turns: Int): Int

    @Query("SELECT * FROM session_records ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SessionRecordEntity>
}

@Dao
interface HabitEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: HabitEventEntity)

    @Query("SELECT * FROM habit_events WHERE date = :date")
    suspend fun getByDate(date: String): List<HabitEventEntity>

    @Query("SELECT * FROM habit_events WHERE date >= :fromDate ORDER BY name ASC, date ASC")
    suspend fun getSince(fromDate: String): List<HabitEventEntity>

    @Query("DELETE FROM habit_events WHERE date < :toDate")
    suspend fun deleteBefore(toDate: String): Int
}
