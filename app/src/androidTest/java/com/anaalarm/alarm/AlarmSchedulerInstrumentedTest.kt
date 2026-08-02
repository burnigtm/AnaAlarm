package com.anaalarm.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.anaalarm.data.AlarmEntity
import com.anaalarm.support.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Talks to the real [android.app.AlarmManager]. Scheduling is verified through the system's
 * "next alarm clock" record, which is what actually wakes the device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AlarmSchedulerInstrumentedTest {

    private val scheduler get() = TestEnv.app.alarmScheduler

    @Before
    fun setUp() {
        TestEnv.clearDatabase()
        assertTrue(
            "SCHEDULE_EXACT_ALARM must be granted for the required alarm suite",
            scheduler.canScheduleExact()
        )
        awaitNoPendingAlarmClock()
    }

    @After
    fun tearDown() {
        TestEnv.clearDatabase()
    }

    @Test
    fun schedulingAnAlarmRegistersItWithTheSystemAlarmClock() {
        val alarm = alarmInFiveMinutes(id = 4001)
        val expected = expectedTrigger(alarm)

        val result = scheduler.schedule(alarm)

        assertTrue(result is AlarmScheduleResult.Scheduled)
        assertTrue(
            "system next-alarm-clock never became $expected",
            TestEnv.waitUntil { nextTrigger() == expected }
        )
    }

    @Test
    fun cancellingRemovesTheSystemAlarmClock() {
        val alarm = alarmInFiveMinutes(id = 4002)
        val expected = expectedTrigger(alarm)
        scheduler.schedule(alarm)
        assertTrue(TestEnv.waitUntil { nextTrigger() == expected })

        scheduler.cancel(alarm)

        assertTrue(
            "alarm was still registered after cancel",
            TestEnv.waitUntil { nextTrigger() != expected }
        )
    }

    @Test
    fun schedulingADisabledAlarmCancelsItInstead() {
        val alarm = alarmInFiveMinutes(id = 4003).copy(enabled = false)
        val expected = expectedTrigger(alarm)

        val result = scheduler.schedule(alarm)

        assertEquals(AlarmScheduleResult.Cancelled, result)
        Thread.sleep(500)
        assertNotEquals(expected, nextTrigger())
    }

    @Test
    fun rescheduleAllReArmsEveryEnabledAlarmFromTheDatabase() = runBlocking {
        val target = LocalDateTime.now().plusMinutes(6)
        val id = TestEnv.app.memoryStore.upsertAlarm(
            id = 0,
            hour = target.hour,
            minute = target.minute,
            days = 0,
            snoozeMinutes = 10,
            enabled = true
        )
        val stored = TestEnv.app.memoryStore.getAlarm(id)!!
        val expected = expectedTrigger(stored)

        scheduler.rescheduleAll()

        assertTrue(
            "rescheduleAll did not arm the stored alarm",
            TestEnv.waitUntil { nextTrigger() == expected }
        )
    }

    @Test
    fun rescheduleNextReArmsASingleStoredAlarmAndToleratesUnknownIds() = runBlocking {
        val target = LocalDateTime.now().plusMinutes(7)
        val id = TestEnv.app.memoryStore.upsertAlarm(0, target.hour, target.minute, 0, 10, true)
        val expected = expectedTrigger(TestEnv.app.memoryStore.getAlarm(id)!!)

        scheduler.rescheduleNext(9_999L) // unknown id must be a no-op, not a crash
        scheduler.rescheduleNext(id)

        assertTrue(TestEnv.waitUntil { nextTrigger() == expected })
    }

    @Test
    fun snoozeUsesTheStoredDurationAndRegistersAnExactAlarm() = runBlocking {
        val id = TestEnv.app.memoryStore.upsertAlarm(0, 6, 30, 0, 7, false)
        val before = LocalDateTime.now()

        val result = scheduler.scheduleSnooze(id)

        assertTrue(result is AlarmScheduleResult.Scheduled)
        val scheduled = result as AlarmScheduleResult.Scheduled
        assertTrue(!scheduled.triggerAt.isBefore(before.plusMinutes(7)))
        assertTrue(!scheduled.triggerAt.isAfter(LocalDateTime.now().plusMinutes(7).plusSeconds(2)))
        assertTrue(TestEnv.waitUntil { nextTrigger() == scheduled.triggerAtMillis })
    }

    @Test
    fun unlockReconciliationRetiresAOneShotThatFiredWhileLocked() = runBlocking {
        val target = LocalDateTime.now().plusMinutes(8)
        val id = TestEnv.app.memoryStore.upsertAlarm(
            id = 0,
            hour = target.hour,
            minute = target.minute,
            days = 0,
            snoozeMinutes = 14,
            enabled = true
        )
        val stored = TestEnv.app.memoryStore.getAlarm(id)!!
        assertTrue(scheduler.schedule(stored) is AlarmScheduleResult.Scheduled)
        assertEquals(FiredAlarmResult.OneShotDisabled, scheduler.handleDirectBootFiredAlarm(id))

        scheduler.reconcileUnlockedNow()

        assertEquals(false, TestEnv.app.memoryStore.getAlarm(id)?.enabled)
        assertEquals(null, scheduler.directBootSnapshot(id))
    }

    @Test
    fun unlockReconciliationCancelsAStaleEnabledMirror() = runBlocking {
        val stale = alarmInFiveMinutes(id = 4007)
        val staleTrigger = expectedTrigger(stale)
        assertTrue(scheduler.schedule(stale) is AlarmScheduleResult.Scheduled)
        assertTrue(TestEnv.waitUntil { nextTrigger() == staleTrigger })

        // The alarm is intentionally absent from Room, modelling a credential-side
        // disable/delete that was interrupted before device-protected cleanup completed.
        scheduler.reconcileUnlockedNow()

        assertEquals(null, scheduler.directBootSnapshot(stale.id))
        assertTrue(TestEnv.waitUntil { nextTrigger() != staleTrigger })
    }

    @Test
    fun nextFireTimeRollsToTomorrowWhenTheTimeHasPassed() {
        val now = LocalDateTime.of(2026, 3, 10, 12, 0)

        val later = scheduler.nextFireTime(hour = 18, minute = 30, days = 0, now = now)
        assertEquals(LocalDateTime.of(2026, 3, 10, 18, 30), later)

        val earlier = scheduler.nextFireTime(hour = 6, minute = 30, days = 0, now = now)
        assertEquals(LocalDateTime.of(2026, 3, 11, 6, 30), earlier)
    }

    @Test
    fun nextFireTimeHonoursTheRepeatBitmask() {
        // Tuesday 2026-03-10 at 12:00; repeat on Saturday only.
        val now = LocalDateTime.of(2026, 3, 10, 12, 0)
        val saturdayOnly = AlarmTriggerCalculator.bitFor(DayOfWeek.SATURDAY)

        val next = scheduler.nextFireTime(hour = 7, minute = 0, days = saturdayOnly, now = now)

        assertEquals(DayOfWeek.SATURDAY, next.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 3, 14, 7, 0), next)
    }

    @Test
    fun bitmaskHelpersMapSundayToBitZero() {
        assertEquals(0, AlarmTriggerCalculator.dayBit(DayOfWeek.SUNDAY))
        assertEquals(6, AlarmTriggerCalculator.dayBit(DayOfWeek.SATURDAY))
        assertEquals(1, AlarmTriggerCalculator.bitFor(DayOfWeek.SUNDAY))
        assertTrue(AlarmTriggerCalculator.hasDay(0b0111110, DayOfWeek.MONDAY))
        assertTrue(!AlarmTriggerCalculator.hasDay(0b0111110, DayOfWeek.SUNDAY))
    }

    private fun alarmInFiveMinutes(id: Long): AlarmEntity {
        val target = LocalDateTime.now().plusMinutes(5)
        return AlarmEntity(id = id, hour = target.hour, minute = target.minute)
    }

    private fun expectedTrigger(alarm: AlarmEntity): Long =
        scheduler.nextFireTime(alarm.hour, alarm.minute, alarm.days)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun nextTrigger(): Long? = TestEnv.alarmManager.nextAlarmClock?.triggerTime

    private fun awaitNoPendingAlarmClock() {
        TestEnv.waitUntil(timeoutMs = 3_000) { nextTrigger() == null }
    }
}
