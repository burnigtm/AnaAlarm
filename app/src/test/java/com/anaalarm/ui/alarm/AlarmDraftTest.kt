package com.anaalarm.ui.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmDraftTest {

    private val saved = AlarmDraft(
        hour = 7,
        minute = 30,
        days = 62,
        snoozeMinutes = 10,
        enabled = true,
        challengeType = 0,
        maxSnoozes = 0,
        ringtoneUri = null
    )

    @Test
    fun `unchanged form is not dirty`() {
        assertFalse(alarmDraftChanged(saved, saved.copy()))
    }

    @Test
    fun `time ringtone and challenge edits are dirty`() {
        assertTrue(alarmDraftChanged(saved, saved.copy(minute = 31)))
        assertTrue(alarmDraftChanged(saved, saved.copy(ringtoneUri = "content://media/1")))
        assertTrue(alarmDraftChanged(saved, saved.copy(challengeType = 1)))
    }
}
