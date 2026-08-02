package com.anaalarm.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.anaalarm.support.TestEnv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises every DAO query against a real SQLite instance on the device. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class AnaDatabaseInstrumentedTest {

    private lateinit var db: AnaDatabase
    private lateinit var alarms: AlarmDao
    private lateinit var messages: MessageDao
    private lateinit var logs: DailyLogDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(TestEnv.context, AnaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarms = db.alarmDao()
        messages = db.messageDao()
        logs = db.dailyLogDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun alarmsAreObservedSortedByTimeOfDay() = runBlocking {
        alarms.upsert(AlarmEntity(hour = 9, minute = 5))
        alarms.upsert(AlarmEntity(hour = 6, minute = 30))
        alarms.upsert(AlarmEntity(hour = 6, minute = 5))

        val observed = alarms.observeAll().first()
        assertEquals(
            listOf(6 to 5, 6 to 30, 9 to 5),
            observed.map { it.hour to it.minute }
        )
    }

    @Test
    fun upsertAssignsIdsAndReplacesOnConflict() = runBlocking {
        val id = alarms.upsert(AlarmEntity(hour = 7, minute = 15, days = 0b0000010))
        assertTrue(id > 0)

        alarms.upsert(AlarmEntity(id = id, hour = 8, minute = 45, days = 0b1000001))

        val reloaded = alarms.getById(id)!!
        assertEquals(8, reloaded.hour)
        assertEquals(45, reloaded.minute)
        assertEquals(0b1000001, reloaded.days)
        assertEquals(1, alarms.observeAll().first().size)
    }

    @Test
    fun getEnabledReturnsOnlyEnabledAlarms() = runBlocking {
        val on = alarms.upsert(AlarmEntity(hour = 6, minute = 0, enabled = true))
        val off = alarms.upsert(AlarmEntity(hour = 7, minute = 0, enabled = false))

        assertEquals(listOf(on), alarms.getEnabled().map { it.id })

        alarms.setEnabled(off, true)
        alarms.setEnabled(on, false)
        assertEquals(listOf(off), alarms.getEnabled().map { it.id })
    }

    @Test
    fun deleteRemovesOnlyTheGivenAlarm() = runBlocking {
        val keep = alarms.upsert(AlarmEntity(hour = 6, minute = 0))
        val drop = alarms.upsert(AlarmEntity(hour = 7, minute = 0))

        alarms.delete(alarms.getById(drop)!!)

        assertNull(alarms.getById(drop))
        assertEquals(listOf(keep), alarms.observeAll().first().map { it.id })
    }

    @Test
    fun alarmTimeMinutesIsDerivedFromHourAndMinute() {
        assertEquals(0, AlarmEntity(hour = 0, minute = 0).timeMinutes)
        assertEquals(6 * 60 + 45, AlarmEntity(hour = 6, minute = 45).timeMinutes)
        assertEquals(23 * 60 + 59, AlarmEntity(hour = 23, minute = 59).timeMinutes)
    }

    @Test
    fun sessionMessagesAreOrderedAndScopedToTheirSession() = runBlocking {
        messages.insert(MessageEntity(sessionId = 1, role = "assistant", content = "hi", timestamp = 10))
        messages.insert(MessageEntity(sessionId = 1, role = "user", content = "morning", timestamp = 20))
        messages.insert(MessageEntity(sessionId = 2, role = "assistant", content = "other", timestamp = 15))

        val session = messages.getSessionMessages(1)
        assertEquals(listOf("hi", "morning"), session.map { it.content })
        assertEquals(listOf("other"), messages.getSessionMessages(2).map { it.content })
    }

    @Test
    fun getLastMessagesReturnsTheNewestFirstUpToTheLimit() = runBlocking {
        repeat(5) { index ->
            messages.insert(
                MessageEntity(
                    sessionId = 7,
                    role = if (index % 2 == 0) "assistant" else "user",
                    content = "m$index",
                    timestamp = index.toLong()
                )
            )
        }

        val newest = messages.getLastMessages(sessionId = 7, limit = 3)
        assertEquals(listOf("m4", "m3", "m2"), newest.map { it.content })
    }

    @Test
    fun clearSessionOnlyWipesThatSession() = runBlocking {
        messages.insert(MessageEntity(sessionId = 1, role = "user", content = "a", timestamp = 1))
        messages.insert(MessageEntity(sessionId = 2, role = "user", content = "b", timestamp = 1))

        messages.clearSession(1)

        assertTrue(messages.getSessionMessages(1).isEmpty())
        assertEquals(1, messages.getSessionMessages(2).size)
    }

    @Test
    fun dailyLogDateIsUniqueAndUpsertReplaces() = runBlocking {
        logs.upsert(DailyLogEntity(date = "2026-01-01", summary = "first"))
        val firstId = logs.getByDate("2026-01-01")!!.id

        logs.upsert(DailyLogEntity(id = firstId, date = "2026-01-01", summary = "second"))

        assertEquals("second", logs.getByDate("2026-01-01")!!.summary)
        assertNull(logs.getByDate("2026-01-02"))
    }

    @Test
    fun getLatestBeforeReturnsTheClosestEarlierDay() = runBlocking {
        logs.upsert(DailyLogEntity(date = "2026-01-01", summary = "oldest"))
        logs.upsert(DailyLogEntity(date = "2026-01-05", summary = "closest"))
        logs.upsert(DailyLogEntity(date = "2026-01-09", summary = "today"))

        assertEquals("closest", logs.getLatestBefore("2026-01-09")!!.summary)
        assertEquals("oldest", logs.getLatestBefore("2026-01-05")!!.summary)
        assertNull(logs.getLatestBefore("2026-01-01"))
    }

    @Test
    fun clearAllTablesEmptiesEveryEntity() = runBlocking {
        alarms.upsert(AlarmEntity(hour = 6, minute = 0))
        messages.insert(MessageEntity(sessionId = 1, role = "user", content = "x", timestamp = 1))
        logs.upsert(DailyLogEntity(date = "2026-02-02", summary = "y"))

        db.clearAllTables()

        assertTrue(alarms.observeAll().first().isEmpty())
        assertTrue(messages.getSessionMessages(1).isEmpty())
        assertNull(logs.getByDate("2026-02-02"))
        assertFalse(db.isOpen.not())
    }
}
