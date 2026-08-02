package com.anaalarm.alarm

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.anaalarm.support.TestEnv
import com.anaalarm.data.AlarmEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/** Alarms must survive a reboot or an app update; [BootReceiver] is what makes that true. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BootReceiverInstrumentedTest {

    private val receiver = BootReceiver()

    @Before
    fun setUp() {
        TestEnv.clearDatabase()
        assertTrue(
            "SCHEDULE_EXACT_ALARM must be granted for the required boot suite",
            TestEnv.app.alarmScheduler.canScheduleExact()
        )
        TestEnv.waitUntil(timeoutMs = 3_000) { nextTrigger() == null }
    }

    @After
    fun tearDown() {
        TestEnv.clearDatabase()
    }

    @Test
    fun bootCompletedReArmsStoredAlarms() {
        val expected = storeEnabledAlarm(minutesFromNow = 8)

        receiver.onReceive(TestEnv.context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue(
            "alarm was not re-armed after BOOT_COMPLETED",
            TestEnv.waitUntil { nextTrigger() == expected }
        )
    }

    @Test
    fun packageReplacedReArmsStoredAlarms() {
        val expected = storeEnabledAlarm(minutesFromNow = 9)

        receiver.onReceive(TestEnv.context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertTrue(
            "alarm was not re-armed after MY_PACKAGE_REPLACED",
            TestEnv.waitUntil { nextTrigger() == expected }
        )
    }

    @Test
    fun wallClockChangeReArmsStoredAlarms() {
        val expected = storeEnabledAlarm(minutesFromNow = 9)

        receiver.onReceive(TestEnv.context, Intent(Intent.ACTION_TIME_CHANGED))

        assertTrue(
            "alarm was not re-armed after TIME_SET",
            TestEnv.waitUntil { nextTrigger() == expected }
        )
    }

    @Test
    fun timeZoneChangeReArmsStoredAlarms() {
        val expected = storeEnabledAlarm(minutesFromNow = 9)

        receiver.onReceive(TestEnv.context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        assertTrue(
            "alarm was not re-armed after TIMEZONE_CHANGED",
            TestEnv.waitUntil { nextTrigger() == expected }
        )
    }

    @Test
    fun exactAlarmPermissionChangeReArmsStoredAlarms() {
        val expected = storeEnabledAlarm(minutesFromNow = 9)

        receiver.onReceive(
            TestEnv.context,
            Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        )

        assertTrue(
            "alarm was not re-armed after exact-alarm permission changed",
            TestEnv.waitUntil { nextTrigger() == expected }
        )
    }

    @Test
    fun unrelatedBroadcastsAreIgnored() {
        val expected = storeEnabledAlarm(minutesFromNow = 10)

        receiver.onReceive(TestEnv.context, Intent(Intent.ACTION_POWER_CONNECTED))

        Thread.sleep(1_500)
        assertNotEquals(expected, nextTrigger())
    }

    @Test
    fun disabledAlarmsAreNotReArmed() = runBlocking {
        val target = LocalDateTime.now().plusMinutes(11)
        val id = TestEnv.app.memoryStore.upsertAlarm(0, target.hour, target.minute, 0, 10, false)
        val stored = TestEnv.app.memoryStore.getAlarm(id)!!
        val expected = triggerOf(stored.hour, stored.minute)

        receiver.onReceive(TestEnv.context, Intent(Intent.ACTION_BOOT_COMPLETED))

        Thread.sleep(1_500)
        assertNotEquals(expected, nextTrigger())
    }

    @Test
    fun lockedBootReArmsFromDeviceProtectedMirrorWithoutRoom() {
        val target = LocalDateTime.now().plusMinutes(12)
        val alarm = AlarmEntity(
            id = 4_009L,
            hour = target.hour,
            minute = target.minute,
            snoozeMinutes = 9
        )
        val expected = triggerOf(alarm.hour, alarm.minute)
        assertTrue(TestEnv.app.alarmScheduler.schedule(alarm) is AlarmScheduleResult.Scheduled)

        // Remove only the framework registration. The device-protected mirror must survive so
        // LOCKED_BOOT_COMPLETED can prove its API-26+ recovery path without AlarmManager.cancelAll.
        TestEnv.app.alarmScheduler.cancelRegular(alarm)
        assertTrue(TestEnv.waitUntil { nextTrigger() == null })
        receiver.onReceive(TestEnv.context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        assertTrue(
            "LOCKED_BOOT_COMPLETED did not use the device-protected mirror",
            TestEnv.waitUntil { nextTrigger() == expected }
        )
    }

    @Test
    fun retiredLockedBootOneShotIsNotResurrected() {
        val target = LocalDateTime.now().plusMinutes(13)
        val alarm = AlarmEntity(
            id = 4_008L,
            hour = target.hour,
            minute = target.minute,
            snoozeMinutes = 8
        )
        assertTrue(TestEnv.app.alarmScheduler.schedule(alarm) is AlarmScheduleResult.Scheduled)
        assertEquals(
            FiredAlarmResult.OneShotDisabled,
            TestEnv.app.alarmScheduler.handleDirectBootFiredAlarm(alarm.id)
        )

        TestEnv.app.alarmScheduler.cancelRegular(alarm)
        receiver.onReceive(TestEnv.context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        Thread.sleep(1_000)
        assertTrue("retired one-shot was re-armed", nextTrigger() == null)
        assertTrue(
            "retirement discarded snooze state",
            TestEnv.app.alarmScheduler.directBootSnoozeMinutes(alarm.id) == 8
        )
    }

    private fun storeEnabledAlarm(minutesFromNow: Long): Long = runBlocking {
        val target = LocalDateTime.now().plusMinutes(minutesFromNow)
        TestEnv.app.memoryStore.upsertAlarm(0, target.hour, target.minute, 0, 10, true)
        triggerOf(target.hour, target.minute)
    }

    private fun triggerOf(hour: Int, minute: Int): Long =
        TestEnv.app.alarmScheduler.nextFireTime(hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun nextTrigger(): Long? = TestEnv.alarmManager.nextAlarmClock?.triggerTime
}
