package com.anaalarm.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.anaalarm.support.TestEnv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Repository-level behaviour against the app's real on-device database — the same instance
 * the running app reads and writes.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class MemoryStoreInstrumentedTest {

    private lateinit var memory: MemoryStore

    @Before
    fun setUp() {
        TestEnv.clearDatabase()
        memory = TestEnv.app.memoryStore
    }

    @After
    fun tearDown() {
        TestEnv.clearDatabase()
    }

    @Test
    fun alarmSurvivesAWriteReadRoundTrip() = runBlocking {
        val id = memory.upsertAlarm(
            id = 0,
            hour = 6,
            minute = 45,
            days = 0b0111110,
            snoozeMinutes = 8,
            enabled = true
        )
        assertTrue(id > 0)

        val loaded = memory.getAlarm(id)!!
        assertEquals(6, loaded.hour)
        assertEquals(45, loaded.minute)
        assertEquals(0b0111110, loaded.days)
        assertEquals(8, loaded.snoozeMinutes)
        assertTrue(loaded.enabled)
    }

    @Test
    fun upsertWithAnExistingIdUpdatesInPlace() = runBlocking {
        val id = memory.upsertAlarm(0, 6, 0, 0, 10, true)
        memory.upsertAlarm(id, 7, 30, 0b0000010, 15, false)

        val loaded = memory.getAlarm(id)!!
        assertEquals(7, loaded.hour)
        assertEquals(30, loaded.minute)
        assertEquals(15, loaded.snoozeMinutes)
        assertEquals(1, memory.alarms.first().size)
    }

    @Test
    fun enabledFlagDrivesGetEnabledAlarms() = runBlocking {
        val a = memory.upsertAlarm(0, 6, 0, 0, 10, enabled = true)
        val b = memory.upsertAlarm(0, 7, 0, 0, 10, enabled = false)

        assertEquals(listOf(a), memory.getEnabledAlarms().map { it.id })

        memory.setAlarmEnabled(a, false)
        memory.setAlarmEnabled(b, true)

        assertEquals(listOf(b), memory.getEnabledAlarms().map { it.id })
    }

    @Test
    fun deleteAlarmRemovesItAndIgnoresUnknownIds() = runBlocking {
        val id = memory.upsertAlarm(0, 6, 0, 0, 10, true)

        memory.deleteAlarm(id)
        assertNull(memory.getAlarm(id))

        memory.deleteAlarm(9_999L) // must not throw
        assertTrue(memory.alarms.first().isEmpty())
    }

    @Test
    fun alarmsFlowEmitsSortedByTime() = runBlocking {
        memory.upsertAlarm(0, 22, 10, 0, 10, true)
        memory.upsertAlarm(0, 5, 45, 0, 10, true)

        assertEquals(
            listOf(5 to 45, 22 to 10),
            memory.alarms.first().map { it.hour to it.minute }
        )
    }

    @Test
    fun sessionHistoryIsChronologicalAndCappedByLimit() = runBlocking {
        val session = memory.beginSession()
        assertTrue(session > 0)

        memory.addMessage(session, "assistant", "good morning")
        memory.addMessage(session, "user", "hello")
        memory.addMessage(session, "assistant", "how did you sleep")

        val full = memory.getSessionHistory(session)
        assertEquals(
            listOf("good morning", "hello", "how did you sleep"),
            full.map { it.content }
        )
        assertEquals(listOf("assistant", "user", "assistant"), full.map { it.role })

        val capped = memory.getSessionHistory(session, limit = 2)
        assertEquals(listOf("hello", "how did you sleep"), capped.map { it.content })
    }

    @Test
    fun historyIsScopedToASingleSession() = runBlocking {
        val first = memory.beginSession()
        memory.addMessage(first, "user", "from first")
        val second = first + 1
        memory.addMessage(second, "user", "from second")

        assertEquals(listOf("from first"), memory.getSessionHistory(first).map { it.content })
        assertEquals(listOf("from second"), memory.getSessionHistory(second).map { it.content })
    }

    @Test
    fun todaySummaryIsEmptyUntilALogIsSavedAndThenOverwrites() = runBlocking {
        assertEquals("", memory.todaySummary())

        memory.saveDailyLog("device summary")
        assertEquals("device summary", memory.todaySummary())

        memory.saveDailyLog("updated summary")
        assertEquals("updated summary", memory.todaySummary())
    }

    @Test
    fun yesterdaySummaryPrefixesTheDateAndIgnoresToday() = runBlocking {
        val yesterday = LocalDate.now().minusDays(1)
        memory.saveDailyLog("today's chat")
        memory.saveDailyLog("watered the plants", yesterday)

        assertEquals("$yesterday: watered the plants", memory.yesterdaySummary())
    }

    @Test
    fun yesterdaySummaryIsEmptyWithoutHistoryOrWithABlankLog() = runBlocking {
        assertEquals("", memory.yesterdaySummary())

        memory.saveDailyLog("   ", LocalDate.now().minusDays(1))
        assertEquals("", memory.yesterdaySummary())
    }

    @Test
    fun yesterdaySummaryPicksTheMostRecentEarlierDay() = runBlocking {
        memory.saveDailyLog("older", LocalDate.now().minusDays(5))
        memory.saveDailyLog("newer", LocalDate.now().minusDays(2))

        val summary = memory.yesterdaySummary()
        assertTrue(summary.endsWith("newer"))
        assertNotNull(summary)
    }
}
