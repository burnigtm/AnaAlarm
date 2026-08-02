package com.anaalarm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmEntityTest {

    @Test
    fun `timeMinutes converts hour and minute`() {
        assertEquals(0, AlarmEntity(hour = 0, minute = 0).timeMinutes)
        assertEquals(450, AlarmEntity(hour = 7, minute = 30).timeMinutes)
        assertEquals(1439, AlarmEntity(hour = 23, minute = 59).timeMinutes)
    }
}
