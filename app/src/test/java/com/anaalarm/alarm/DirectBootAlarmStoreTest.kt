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
        store = DirectBootAlarmStore(context)
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
        assertNull(DirectBootAlarmStore.decode("v1|1|99|0|0|10|enabled"))
        assertNull(DirectBootAlarmStore.decode("v1|1|7|0|128|10|enabled"))
        assertTrue(DirectBootAlarmStore.encode(DirectBootAlarm(3L, 5, 20, 0, 10)).isNotBlank())
    }

    @Test
    fun `corrupt persisted entries are dropped durably`() {
        val preferences = context.createDeviceProtectedStorageContext().getSharedPreferences(
            DirectBootAlarmStore.PREFERENCES_NAME,
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
}
