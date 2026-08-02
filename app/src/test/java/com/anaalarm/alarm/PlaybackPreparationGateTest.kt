package com.anaalarm.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreparationGateTest {

    @Test
    fun `completion from stopped alarm cannot attach after restart`() {
        val released = mutableListOf<FakePlayer>()
        val gate = PlaybackPreparationGate<FakePlayer> { released += it }
        val stoppedToken = gate.begin(alarmId = 11L)

        gate.invalidate()
        val restartedToken = gate.begin(alarmId = 12L)
        val stalePlayer = FakePlayer("stale")
        val restartedPlayer = FakePlayer("restarted")

        assertFalse(gate.attach(stoppedToken, stalePlayer))
        assertTrue(gate.attach(restartedToken, restartedPlayer))
        assertTrue(gate.owns(restartedToken, restartedPlayer))
        assertEquals(listOf(stalePlayer), released)
    }

    @Test
    fun `out of order stale completion cannot replace current player`() {
        val released = mutableListOf<FakePlayer>()
        val gate = PlaybackPreparationGate<FakePlayer> { released += it }
        val firstToken = gate.begin(alarmId = 21L)

        gate.invalidate()
        val secondToken = gate.begin(alarmId = 22L)
        val currentPlayer = FakePlayer("current")
        val lateFirstPlayer = FakePlayer("late-first")

        assertTrue(gate.attach(secondToken, currentPlayer))
        assertFalse(gate.attach(firstToken, lateFirstPlayer))
        assertTrue(gate.owns(secondToken, currentPlayer))
        assertEquals(listOf(lateFirstPlayer), released)
    }

    @Test
    fun `replacement releases prior player before assigning new player`() {
        val released = mutableListOf<FakePlayer>()
        val ownershipObservedDuringRelease = mutableListOf<Boolean>()
        lateinit var gate: PlaybackPreparationGate<FakePlayer>
        gate = PlaybackPreparationGate { player ->
            ownershipObservedDuringRelease += gate.hasAttachment()
            released += player
        }
        val token = gate.begin(alarmId = 31L)
        val firstPlayer = FakePlayer("first")
        val replacementPlayer = FakePlayer("replacement")
        assertTrue(gate.attach(token, firstPlayer))

        assertTrue(gate.attach(token, replacementPlayer))

        assertEquals(listOf(firstPlayer), released)
        assertEquals(listOf(false), ownershipObservedDuringRelease)
        assertTrue(gate.owns(token, replacementPlayer))
    }

    @Test
    fun `stop invalidation releases the attached player`() {
        val released = mutableListOf<FakePlayer>()
        val gate = PlaybackPreparationGate<FakePlayer> { released += it }
        val token = gate.begin(alarmId = 41L)
        val player = FakePlayer("playing")
        assertTrue(gate.attach(token, player))

        gate.invalidate()

        assertFalse(gate.hasAttachment())
        assertFalse(gate.owns(token, player))
        assertEquals(listOf(player), released)
    }

    private data class FakePlayer(val name: String)
}
