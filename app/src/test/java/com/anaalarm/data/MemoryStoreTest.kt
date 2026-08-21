package com.anaalarm.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MemoryStoreTest {

    private lateinit var db: AnaDatabase
    private lateinit var memory: MemoryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AnaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        memory = MemoryStore(db, SettingsStore(context))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `alarm upsert update delete and enabled filter`() = runTest {
        val id = memory.upsertAlarm(
            id = 0,
            hour = 7,
            minute = 30,
            days = 2,
            snoozeMinutes = 5,
            enabled = true
        )
        assertTrue(id > 0)

        memory.upsertAlarm(id, 8, 0, 4, 10, true)
        val updated = memory.getAlarm(id)!!
        assertEquals(8, updated.hour)
        assertEquals(0, updated.minute)
        assertEquals(4, updated.days)

        memory.setAlarmEnabled(id, false)
        assertTrue(memory.getEnabledAlarms().isEmpty())

        memory.setAlarmEnabled(id, true)
        assertEquals(1, memory.getEnabledAlarms().size)

        memory.deleteAlarm(id)
        assertNull(memory.getAlarm(id))
        assertTrue(memory.alarms.first().isEmpty())
    }

    @Test
    fun `session history returns chronological limited messages`() = runTest {
        val sessionId = 42L
        memory.addMessage(sessionId, "assistant", "hi")
        memory.addMessage(sessionId, "user", "hello")
        memory.addMessage(sessionId, "assistant", "how are you")

        val history = memory.getSessionHistory(sessionId, limit = 2)
        assertEquals(
            listOf(
                ChatMessage("user", "hello"),
                ChatMessage("assistant", "how are you")
            ),
            history
        )
    }

    @Test
    fun `clear session removes raw transcript but leaves other sessions`() = runTest {
        memory.addMessage(41L, "user", "private morning detail")
        memory.addMessage(42L, "user", "keep until its session ends")

        memory.clearSession(41L)

        assertTrue(memory.getSessionHistory(41L).isEmpty())
        assertEquals(
            listOf(ChatMessage("user", "keep until its session ends")),
            memory.getSessionHistory(42L)
        )
    }

    @Test
    fun `prune removes only raw messages older than cutoff`() = runTest {
        val dao = db.messageDao()
        dao.insert(MessageEntity(sessionId = 1L, role = "user", content = "old", timestamp = 10L))
        dao.insert(MessageEntity(sessionId = 2L, role = "user", content = "new", timestamp = 30L))

        assertEquals(1, memory.pruneMessagesOlderThan(20L))
        assertTrue(memory.getSessionHistory(1L).isEmpty())
        assertEquals(listOf("new"), memory.getSessionHistory(2L).map { it.content })
    }

    @Test
    fun `rapid session allocation remains unique and monotonic`() = runTest {
        val ids = List(100) { memory.beginSession() }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `daily log upsert replaces same date instead of duplicating`() = runTest {
        val day = LocalDate.of(2026, 8, 1)
        memory.saveDailyLog("first summary", day)
        memory.saveDailyLog("second summary", day)

        val row = db.dailyLogDao().getByDate(day.toString())
        assertEquals("second summary", row?.summary)

        // Unique date index: still a single row for that day.
        memory.saveDailyLog("third", day)
        assertEquals("third", db.dailyLogDao().getByDate(day.toString())?.summary)
    }

    @Test
    fun `append daily log preserves multiple sessions on the same date`() = runTest {
        val day = LocalDate.of(2026, 8, 1)
        memory.appendDailyLog("first session", day)
        memory.appendDailyLog("second session", day)
        memory.appendDailyLog("   ", day)

        assertEquals(
            "first session\nsecond session",
            db.dailyLogDao().getByDate(day.toString())?.summary
        )
    }

    @Test
    fun `yesterdaySummary formats previous day entry`() = runTest {
        val yesterday = LocalDate.now().minusDays(1)
        memory.saveDailyLog("watered plants", yesterday)

        val summary = memory.yesterdaySummary()
        assertTrue(summary.startsWith(yesterday.toString()))
        assertTrue(summary.contains("watered plants"))
    }

    @Test
    fun `todaySummary reads saved log for today`() = runTest {
        memory.saveDailyLog("today plan")
        assertEquals("today plan", memory.todaySummary())
    }

    @Test
    fun `append daily log keeps the most recent tail when the cap is exceeded`() = runTest {
        val day = LocalDate.of(2026, 8, 1)
        val first = "x".repeat(1_500)
        val second = "y".repeat(600)

        memory.appendDailyLog(first, day)
        memory.appendDailyLog(second, day)

        val stored = db.dailyLogDao().getByDate(day.toString())?.summary.orEmpty()
        // Combined length exceeds MAX_DAILY_LOG_CHARS (1801): only the tail survives.
        assertEquals(1_801, stored.length)
        assertTrue(stored.endsWith(second))
        assertTrue(stored.startsWith("x"))
    }

    @Test
    fun `yesterdaySummary is empty without any earlier log`() = runTest {
        assertEquals("", memory.yesterdaySummary())
    }

    @Test
    fun `yesterdaySummary skips a blank stored summary`() = runTest {
        val twoDaysAgo = LocalDate.now().minusDays(2)
        memory.saveDailyLog("   ", twoDaysAgo)
        assertEquals("", memory.yesterdaySummary())
    }

    @Test
    fun `yesterdaySummary falls back to the raw date when it cannot be parsed`() = runTest {
        // Sorts before today's ISO date but is not a valid LocalDate.
        db.dailyLogDao().upsert(DailyLogEntity(date = "1999-99-99", summary = "kept as written"))

        val summary = memory.yesterdaySummary()
        assertTrue(summary.startsWith("1999-99-99"))
        assertTrue(summary.contains("kept as written"))
    }

    @Test
    fun `usage rows aggregate over date windows`() = runTest {
        val today = LocalDate.now()
        memory.recordUsage(today, 1L, inputTokens = 100, outputTokens = 20, cachedTokens = 60, totalTokens = 120)
        memory.recordUsage(today.minusDays(3), 1L, inputTokens = 50, outputTokens = 10, cachedTokens = 0, totalTokens = 60)
        memory.recordUsage(today.minusDays(10), 2L, inputTokens = 999, outputTokens = 999, cachedTokens = 0, totalTokens = 1998)

        val week = memory.usageSummary(daysBack = 6)
        assertEquals(150L, week.inputTokens)
        assertEquals(30L, week.outputTokens)
        assertEquals(60L, week.cachedTokens)
        assertEquals(180L, week.totalTokens)
        assertEquals(2, week.requests)

        val todayOnly = memory.usageSummary(daysBack = 0)
        assertEquals(100L, todayOnly.inputTokens)
        assertEquals(1, todayOnly.requests)

        assertEquals(3, memory.usageSummary(daysBack = 30).requests)
    }

    @Test
    fun `empty usage ledger aggregates to zeros without failing`() = runTest {
        val empty = memory.usageSummary(daysBack = 7)
        assertEquals(0L, empty.totalTokens)
        assertEquals(0, empty.requests)
    }

    @Test
    fun `session records persist their shape and read back newest first`() = runTest {
        memory.recordSession(startedAtMillis = 1_000L, endedAtMillis = 61_000L, turns = 5)
        memory.recordSession(startedAtMillis = 2_000L, endedAtMillis = 3_000L, turns = 1)
        // A degenerate record (no turns, no duration) is ignored entirely.
        memory.recordSession(startedAtMillis = 9_000L, endedAtMillis = 9_000L, turns = 0)

        val records = memory.recentSessionRecords(limit = 10)
        assertEquals(2, records.size)
        assertEquals(2_000L, records.first().startedAt)
        assertEquals(60_000L, records[1].durationMs)
        assertEquals(5, records[1].turns)
    }
}
