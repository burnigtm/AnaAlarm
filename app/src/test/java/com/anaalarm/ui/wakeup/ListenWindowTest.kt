package com.anaalarm.ui.wakeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenWindowTest {

    @Test
    fun `listen window is twenty five seconds`() {
        assertEquals(25_000L, SessionListenCycle.LISTEN_TIMEOUT_MS)
    }

    @Test
    fun `remaining seconds round up and stop at zero`() {
        val deadline = 25_000L
        assertEquals(25, listenSecondsRemaining(deadline, 0L))
        assertEquals(25, listenSecondsRemaining(deadline, 1L))
        assertEquals(1, listenSecondsRemaining(deadline, 24_001L))
        assertEquals(0, listenSecondsRemaining(deadline, 25_000L))
        assertEquals(0, listenSecondsRemaining(deadline, 30_000L))
    }

    @Test
    fun `only speaking listening and thinking pulse`() {
        assertTrue(wakePhasePulses(SessionStatus.SPEAKING))
        assertTrue(wakePhasePulses(SessionStatus.LISTENING))
        assertTrue(wakePhasePulses(SessionStatus.THINKING))
        assertFalse(wakePhasePulses(SessionStatus.STARTING))
        assertFalse(wakePhasePulses(SessionStatus.ENDED))
    }
}
