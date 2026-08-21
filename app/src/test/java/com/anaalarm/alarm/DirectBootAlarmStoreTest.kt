package com.anaalarm.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DirectBootAlarmStoreTest {

    private lateinit var context: Context
    private lateinit var store: DirectBootAlarmStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // AnaAlarmApp reconciles the production mirror asynchronously during Robolectric startup.
        // A dedicated file keeps this storage unit test deterministic without bypassing the
        // device-protected SharedPreferences path used in production.
        store = DirectBootAlarmStore(context, TEST_PREFERENCES_NAME)
        store.clearForTest()
    }

    @After
    fun tearDown() {
        store.clearForTest()
    }

    @Test
    fun `round trip contains only minimal schedule and snooze state`() {
        val alarm = DirectBootAlarm(41L, 6, 35, 0b0111110, 9)

        store.upsert(alarm)

        assertEquals(alarm, store.get(41L))
        assertEquals(listOf(alarm), store.enabledAlarms())
    }

    @Test
    fun `retired one shot is excluded from rearm but retains snooze duration`() {
        store.upsert(DirectBootAlarm(42L, 7, 0, 0, 11))

        val retired = store.retireOneShot(42L)

        assertFalse(retired!!.enabledForRearm)
        assertEquals(11, retired.snoozeMinutes)
        assertTrue(store.enabledAlarms().isEmpty())
        assertEquals(listOf(retired), store.retiredOneShots())
    }

    @Test
    fun `replacing enabled set preserves an unacknowledged retirement`() {
        store.upsert(DirectBootAlarm(1L, 6, 0, 0, 5))
        store.retireOneShot(1L)

        store.replaceEnabled(listOf(DirectBootAlarm(2L, 8, 30, 62, 7)))

        assertEquals(listOf(2L), store.enabledAlarms().map { it.id })
        assertEquals(listOf(1L), store.retiredOneShots().map { it.id })
    }

    @Test
    fun `malformed values fail closed`() {
        assertNull(DirectBootAlarmStore.decode("not-a-schedule"))
        assertNull(DirectBootAlarmStore.decode("v2|1|99|0|0|10|enabled|0|0|"))
        assertNull(DirectBootAlarmStore.decode("v2|1|7|0|128|10|enabled|0|0|"))
        assertTrue(
            DirectBootAlarmStore.encode(DirectBootAlarm(3L, 5, 20, 0, 10)).isNotBlank()
        )
    }

    @Test
    fun `direct boot alarm validation rejects impossible state`() {
        fun alarm(
            id: Long = 1L,
            hour: Int = 7,
            minute: Int = 0,
            days: Int = 0,
            snoozeMinutes: Int = 10,
            enabledForRearm: Boolean = true
        ) = DirectBootAlarm(id, hour, minute, days, snoozeMinutes, enabledForRearm)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { alarm(id = -1L) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { alarm(hour = 24) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { alarm(minute = 60) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { alarm(days = 128) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { alarm(snoozeMinutes = 0) }
        // Only a one-shot (days == 0) may be retired.
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            alarm(days = 2, enabledForRearm = false)
        }
    }

    @Test
    fun `v2 round trip carries snooze counter cap and ringtone`() {
        val alarm = DirectBootAlarm(
            id = 12L,
            hour = 6,
            minute = 5,
            days = 0b1111111,
            snoozeMinutes = 9,
            maxSnoozes = 3,
            ringtoneUri = "content://media/internal/audio/media/5"
        )
        store.upsert(alarm)
        assertEquals(alarm, store.get(12L))
        val decoded = DirectBootAlarmStore.decode(DirectBootAlarmStore.encode(alarm))
        assertEquals(alarm, decoded)
    }

    @Test
    fun `legacy v1 snapshots decode without counter or ringtone`() {
        val decoded = DirectBootAlarmStore.decode("v1|9|6|15|0|4|enabled")
        assertEquals(DirectBootAlarm(9L, 6, 15, 0, 4), decoded)
        assertNull(DirectBootAlarmStore.decode("v1|9|6|15|0|4|unknown-state"))
        assertNull(DirectBootAlarmStore.decode("v3|9|6|15|0|4|enabled|0|0|"))
    }

    @Test
    fun `snooze counter increments and resets durably`() {
        store.upsert(DirectBootAlarm(30L, 7, 0, 0, 10, maxSnoozes = 2))

        assertEquals(1, store.incrementSnoozeCount(30L)?.snoozeCount)
        assertEquals(2, store.incrementSnoozeCount(30L)?.snoozeCount)
        assertEquals(2, store.get(30L)?.snoozeCount)

        assertEquals(0, store.resetSnoozeCount(30L)?.snoozeCount)
        assertEquals(0, store.get(30L)?.snoozeCount)
        assertNull(store.incrementSnoozeCount(404L))
        assertNull(store.resetSnoozeCount(404L))
    }

    @Test
    fun `upsert and replaceEnabled preserve an in-flight snooze count`() {
        store.upsert(DirectBootAlarm(31L, 7, 0, 0, 10, maxSnoozes = 3))
        store.incrementSnoozeCount(31L)
        store.incrementSnoozeCount(31L)

        // A re-arm or unlock reconciliation rewrites the snapshot from Room defaults.
        store.upsert(DirectBootAlarm(31L, 7, 0, 0, 10, maxSnoozes = 3))
        assertEquals(2, store.get(31L)?.snoozeCount)

        store.replaceEnabled(listOf(DirectBootAlarm(31L, 7, 0, 0, 10, maxSnoozes = 3)))
        assertEquals(2, store.get(31L)?.snoozeCount)
    }

    @Test
    fun `corrupt persisted entries are dropped durably`() {
        val preferences = context.createDeviceProtectedStorageContext().getSharedPreferences(
            TEST_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        preferences.edit()
            .putString("alarm.80", "v1|80|7|0|128|10|enabled")
            .putString("alarm.81", "v1|82|7|0|0|10|enabled")
            .putString("alarm.83", "v1|83|7|0|2|10|retired")
            .commit()

        assertTrue(store.enabledAlarms().isEmpty())
        assertFalse(preferences.contains("alarm.80"))
        assertFalse(preferences.contains("alarm.81"))
        assertFalse(preferences.contains("alarm.83"))
    }

    private companion object {
        const val TEST_PREFERENCES_NAME = "direct_boot_alarms_store_test"
    }
}
