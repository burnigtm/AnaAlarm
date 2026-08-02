package com.anaalarm.alarm

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.anaalarm.support.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
        assumeTrue(TestEnv.app.alarmScheduler.canScheduleExact())
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
