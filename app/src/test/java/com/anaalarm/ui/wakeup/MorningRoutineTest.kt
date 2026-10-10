package com.anaalarm.ui.wakeup

import org.junit.Assert.*
import org.junit.Test

class MorningRoutineTest {
    @Test fun `completed blank and duplicate habits are excluded preserving order`() {
        val routine = MorningRoutine(listOf(" Water ", "", "Walk", "Water", "Read"), setOf("Walk"))
        assertEquals(2, routine.total)
        assertEquals("Water", routine.habit)
        routine.advance()
        assertEquals("Read", routine.habit)
        routine.advance()
        assertTrue(routine.awaitingPriority)
        assertEquals(2, routine.index)
    }
    @Test fun `an empty routine immediately asks for a priority`() {
        val routine = MorningRoutine(emptyList(), emptySet())
        assertNull(routine.habit)
        assertTrue(routine.awaitingPriority)
        routine.finish()
        assertTrue(routine.finished)
        assertFalse(routine.awaitingPriority)
    }
    @Test fun `finish cannot skip remaining habits and advancing after completion is idempotent`() {
        val routine = MorningRoutine(listOf("Water"), emptySet())
        routine.finish()
        assertFalse(routine.finished)
        routine.advance(); routine.advance()
        routine.finish(); routine.finish(); routine.advance()
        assertEquals(1, routine.index)
        assertTrue(routine.finished)
    }
    @Test fun `relaunch derives remaining steps from persisted completions`() {
        val routine = MorningRoutine(listOf("A", "B", "C"), setOf("A", "C"))
        assertEquals("B", routine.habit)
        assertEquals(1, routine.total)
    }
}
