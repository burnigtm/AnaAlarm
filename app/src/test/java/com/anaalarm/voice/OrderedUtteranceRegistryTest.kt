package com.anaalarm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedUtteranceRegistryTest {

    private fun entry(
        id: String,
        turnId: Long = 1,
        sequence: Int = 0
    ) = OrderedUtteranceRegistry.Entry(
        id = id,
        turnId = turnId,
        sequence = sequence,
        onStart = {},
        onComplete = {},
        watchdog = Runnable {}
    )

    @Test
    fun `register start and finish round-trip one utterance`() {
        val registry = OrderedUtteranceRegistry()
        registry.register(entry("t1-0", sequence = 0))
        assertEquals(1, registry.outstandingCount())

        assertNotNull(registry.markStarted("t1-0"))
        // A second start callback for the same utterance is stale and rejected.
        assertNull(registry.markStarted("t1-0"))

        assertNotNull(registry.finish("t1-0"))
        assertNull(registry.finish("t1-0"))
        assertEquals(0, registry.outstandingCount())
    }

    @Test
    fun `unknown ids are rejected everywhere`() {
        val registry = OrderedUtteranceRegistry()
        assertNull(registry.markStarted("missing"))
        assertNull(registry.finish("missing"))
        assertNull(registry.get("missing"))
    }

    @Test
    fun `clearTurn removes only that turn's utterances in enqueue order`() {
        val registry = OrderedUtteranceRegistry()
        registry.register(entry("a", turnId = 1, sequence = 0))
        registry.register(entry("b", turnId = 2, sequence = 1))
        registry.register(entry("c", turnId = 1, sequence = 2))

        val removed = registry.clearTurn(1)
        assertEquals(listOf("a", "c"), removed.map { it.id })
        assertEquals(1, registry.outstandingCount())
        assertNull(registry.get("a"))
        assertNotNull(registry.get("b"))
    }

    @Test
    fun `clearAll empties the registry`() {
        val registry = OrderedUtteranceRegistry()
        registry.register(entry("a"))
        registry.register(entry("b"))
        assertEquals(2, registry.clearAll().size)
        assertEquals(0, registry.outstandingCount())
    }

    @Test
    fun `unstartedCount tracks audibility per turn`() {
        val registry = OrderedUtteranceRegistry()
        registry.register(entry("s1", turnId = 7, sequence = 1))
        registry.register(entry("s2", turnId = 7, sequence = 2))
        registry.register(entry("other", turnId = 8, sequence = 1))

        registry.markStarted("s1")
        assertEquals(1, registry.unstartedCount(7))
        assertEquals(1, registry.unstartedCount(8))
        registry.finish("s2")
        assertEquals(0, registry.unstartedCount(7))
    }

    @Test
    fun `re-registering an id replaces the stored entry`() {
        val registry = OrderedUtteranceRegistry()
        registry.register(entry("dup", sequence = 1))
        val replacement = entry("dup", sequence = 2)
        registry.register(replacement)
        assertTrue(registry.get("dup") === replacement)
        assertEquals(1, registry.outstandingCount())
    }
}
