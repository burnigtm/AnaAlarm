package com.anaalarm.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmReceiverTest {

    @Test
    fun `private scheduled action is accepted in release and debug builds`() {
        assertTrue(AlarmReceiver.accepts(AlarmReceiver.ACTION_SCHEDULED_ALARM, false))
        assertTrue(AlarmReceiver.accepts(AlarmReceiver.ACTION_SCHEDULED_ALARM, true))
    }

    @Test
    fun `adb fire action is accepted only in debug builds`() {
        assertFalse(AlarmReceiver.accepts(AlarmReceiver.ACTION_DEBUG_FIRE_ALARM, false))
        assertTrue(AlarmReceiver.accepts(AlarmReceiver.ACTION_DEBUG_FIRE_ALARM, true))
    }

    @Test
    fun `unknown or missing actions are rejected`() {
        assertFalse(AlarmReceiver.accepts(null, true))
        assertFalse(AlarmReceiver.accepts("com.anaalarm.action.UNKNOWN", true))
    }

    @Test
    fun `snooze delivery skips regular one-shot or repeat handling`() {
        assertFalse(AlarmReceiver.shouldHandleRegularScheduleAfterFire(isSnooze = true))
        assertTrue(AlarmReceiver.shouldHandleRegularScheduleAfterFire(isSnooze = false))
    }

    @Test
    fun `credential storage is never selected before first unlock`() {
        assertTrue(AlarmReceiver.shouldUseDirectBootState(userUnlocked = false))
        assertFalse(AlarmReceiver.shouldUseDirectBootState(userUnlocked = true))
    }
}
