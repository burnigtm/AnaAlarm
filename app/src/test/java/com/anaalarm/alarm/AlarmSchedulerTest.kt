package com.anaalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anaalarm.data.AlarmEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmSchedulerTest {

    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: AlarmScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        alarmManager = mockk(relaxed = true)
        scheduler = AlarmScheduler(
            context = context,
            alarmManager = alarmManager,
            directBootPreferencesName = TEST_DIRECT_BOOT_PREFERENCES
        )
        scheduler.clearDirectBootStoreForTest()
    }

    @Test
    fun `permission denial is returned and no alarm is registered`() {
        every { alarmManager.canScheduleExactAlarms() } returns false

        val result = scheduler.schedule(AlarmEntity(id = 1, hour = 7, minute = 30))

        assertEquals(
            AlarmScheduleResult.Failed.Reason.EXACT_ALARM_PERMISSION_REQUIRED,
            (result as AlarmScheduleResult.Failed).reason
        )
        verify(exactly = 0) { alarmManager.setAlarmClock(any(), any()) }
    }

    @Test
    fun `invalid alarm data is reported without touching AlarmManager`() {
        every { alarmManager.canScheduleExactAlarms() } returns true

        val result = scheduler.schedule(AlarmEntity(id = 2, hour = 25, minute = 0))

        assertEquals(
            AlarmScheduleResult.Failed.Reason.INVALID_ALARM,
            (result as AlarmScheduleResult.Failed).reason
        )
        verify(exactly = 0) { alarmManager.setAlarmClock(any(), any()) }
    }

    @Test
    fun `disabled alarm cancels pending intents and reports cancelled`() {
        val result = scheduler.schedule(
            AlarmEntity(id = 3, hour = 8, minute = 0, enabled = false)
        )

        assertEquals(AlarmScheduleResult.Cancelled, result)
        verify(exactly = 3) { alarmManager.cancel(any<PendingIntent>()) }
    }

    @Test
    fun `post-fire regular cleanup preserves a newly scheduled snooze`() {
        val cancelled = mutableListOf<PendingIntent>()
        val alarm = AlarmEntity(id = 31, hour = 8, minute = 0)

        scheduler.cancelRegular(alarm)

        verify(exactly = 2) { alarmManager.cancel(capture(cancelled)) }
        assertTrue(
            cancelled.none {
                shadowOf(it).savedIntent.getBooleanExtra(AlarmReceiver.EXTRA_IS_SNOOZE, false)
            }
        )
    }

    @Test
    fun `scheduled PendingIntent uses the private production action`() {
        every { alarmManager.canScheduleExactAlarms() } returns true
        val operation = slot<PendingIntent>()

        val result = scheduler.schedule(AlarmEntity(id = 4, hour = 9, minute = 15))

        assertTrue(result is AlarmScheduleResult.Scheduled)
        verify { alarmManager.setAlarmClock(any(), capture(operation)) }
        val savedIntent = shadowOf(operation.captured).savedIntent
        assertEquals(AlarmReceiver.ACTION_SCHEDULED_ALARM, savedIntent.action)
        assertEquals(AlarmReceiver::class.java.name, savedIntent.component?.className)
    }

    @Test
    fun `snooze uses its own exact PendingIntent and persisted duration`() {
        every { alarmManager.canScheduleExactAlarms() } returns true
        val operations = mutableListOf<PendingIntent>()
        val alarm = AlarmEntity(id = 5, hour = 9, minute = 15, snoozeMinutes = 12)
        val now = LocalDateTime.of(2026, 8, 2, 7, 30, 45)

        scheduler.schedule(alarm)
        val result = scheduler.scheduleSnooze(alarm, now)

        verify(exactly = 2) { alarmManager.setAlarmClock(any(), capture(operations)) }
        val regularShadow = shadowOf(operations[0])
        val snoozeShadow = shadowOf(operations[1])
        assertTrue(result is AlarmScheduleResult.Scheduled)
        assertEquals(now.plusMinutes(12), (result as AlarmScheduleResult.Scheduled).triggerAt)
        assertTrue(snoozeShadow.savedIntent.getBooleanExtra(AlarmReceiver.EXTRA_IS_SNOOZE, false))
        assertEquals(AlarmReceiver.ACTION_SCHEDULED_ALARM, snoozeShadow.savedIntent.action)
        assertTrue(regularShadow.requestCode != snoozeShadow.requestCode)
    }

    @Test
    fun `invalid snooze duration is rejected`() {
        every { alarmManager.canScheduleExactAlarms() } returns true
        val alarm = AlarmEntity(id = 6, hour = 10, minute = 0, snoozeMinutes = 0)

        val result = scheduler.scheduleSnooze(alarm)

        assertEquals(
            AlarmScheduleResult.Failed.Reason.INVALID_ALARM,
            (result as AlarmScheduleResult.Failed).reason
        )
        verify(exactly = 0) { alarmManager.setAlarmClock(any(), any()) }
    }

    @Test
    fun `enabled schedules are mirrored and cancellation removes the mirror`() {
        every { alarmManager.canScheduleExactAlarms() } returns true
        val alarm = AlarmEntity(id = 70, hour = 6, minute = 40, days = 2, snoozeMinutes = 13)

        scheduler.schedule(alarm)

        assertEquals(
            DirectBootAlarm(70, 6, 40, 2, 13),
            scheduler.directBootSnapshot(70)
        )

        scheduler.cancel(alarm)
        assertEquals(null, scheduler.directBootSnapshot(70))
    }

    @Test
    fun `locked one shot retires without rearming and can be snoozed repeatedly`() {
        every { alarmManager.canScheduleExactAlarms() } returns true
        val alarm = AlarmEntity(id = 71, hour = 7, minute = 15, days = 0, snoozeMinutes = 12)
        val now = LocalDateTime.of(2026, 8, 2, 6, 0)
        scheduler.schedule(alarm)

        assertEquals(FiredAlarmResult.OneShotDisabled, scheduler.handleDirectBootFiredAlarm(71))
        assertFalse(scheduler.directBootSnapshot(71)!!.enabledForRearm)
        scheduler.rescheduleDirectBootNow()
        verify(exactly = 1) { alarmManager.setAlarmClock(any(), any()) }

        val first = scheduler.scheduleDirectBootSnooze(71, now)
        val second = scheduler.scheduleDirectBootSnooze(71, now.plusMinutes(12))

        assertEquals(now.plusMinutes(12), (first as AlarmScheduleResult.Scheduled).triggerAt)
        assertEquals(now.plusMinutes(24), (second as AlarmScheduleResult.Scheduled).triggerAt)
        verify(exactly = 3) { alarmManager.setAlarmClock(any(), any()) }
    }

    @Test
    fun `locked repeating alarm is rearmed from the device snapshot`() {
        every { alarmManager.canScheduleExactAlarms() } returns true
        val alarm = AlarmEntity(id = 72, hour = 8, minute = 5, days = 0b0111110)
        scheduler.schedule(alarm)

        val result = scheduler.handleDirectBootFiredAlarm(72)

        assertTrue(result is FiredAlarmResult.RepeatingRearmed)
        assertTrue(scheduler.directBootSnapshot(72)!!.enabledForRearm)
        verify(exactly = 2) { alarmManager.setAlarmClock(any(), any()) }
    }

    private companion object {
        const val TEST_DIRECT_BOOT_PREFERENCES = "alarm_scheduler_test"
    }
}
