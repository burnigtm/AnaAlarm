package com.anaalarm.voice

import com.anaalarm.telemetry.LatencyBoundary
import com.anaalarm.telemetry.LatencyEvent
import com.anaalarm.telemetry.LatencyEventSink
import com.anaalarm.telemetry.LatencyMetric
import com.anaalarm.telemetry.LatencyMetrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceRegistryTest {

    @Test
    fun staleCallbacksCannotFinishTheActiveUtterance() {
        val registry = UtteranceRegistry()
        val active = entry("new")
        registry.replace(active)

        assertNull(registry.markStarted("old"))
        assertNull(registry.finish("old"))
        assertSame(active.onStart, registry.markStarted("new"))
        assertNull("onStart must run at most once", registry.markStarted("new"))
        assertSame(active, registry.finish("new"))
        assertNull(registry.finish("new"))
    }

    @Test
    fun replacingAnUtteranceReturnsTheFlushedEntry() {
        val registry = UtteranceRegistry()
        val first = entry("first")
        val second = entry("second")

        assertNull(registry.replace(first))
        assertSame(first, registry.replace(second))
        assertNull("flushed callbacks must be stale", registry.markStarted("first"))
        assertSame(second.onStart, registry.markStarted("second"))
        assertSame(second, registry.clear())
        assertNull(registry.clear())
    }

    @Test
    fun callbacksBelongToTheirOwnEntry() {
        var started = false
        var completed = false
        val registry = UtteranceRegistry()
        val entry = UtteranceRegistry.Entry(
            id = "id",
            onStart = { started = true },
            onComplete = { completed = true },
            watchdog = Runnable { }
        )
        registry.replace(entry)

        registry.markStarted("id")?.invoke(123L)
        registry.finish("id")?.onComplete?.invoke()

        assertTrue(started)
        assertTrue(completed)
        assertEquals("id", entry.id)
        assertFalse(registry.finish("missing") != null)
    }

    @Test
    fun supersededAndCancelledUtterancesEachEmitOneTerminalBoundary() = runBlocking {
        val registry = UtteranceRegistry()
        val events = mutableListOf<LatencyEvent>()
        val firstBoundary = ttsBoundary()
        val secondBoundary = ttsBoundary()
        val first = latencyEntry("first", firstBoundary)
        val second = latencyEntry("second", secondBoundary)

        LatencyMetrics.withEventSink(LatencyEventSink { events.add(it) }) {
            registry.replace(first)
            registry.replace(second)?.onStartMissing?.invoke("superseded")

            assertNull("superseded start callback must be stale", registry.markStarted("first"))
            assertNull("superseded completion must be stale", registry.finish("first"))

            registry.clear()?.onStartMissing?.invoke("cancelled")
            assertNull("cancelled start callback must be stale", registry.markStarted("second"))
            assertNull("cancelled completion must be stale", registry.finish("second"))
            assertNull("cleanup must be idempotent", registry.clear())
        }

        assertEquals(listOf("superseded", "cancelled"), events.map { it.outcome })
    }

    private fun entry(id: String) = UtteranceRegistry.Entry(
        id = id,
        onStart = {},
        onComplete = {},
        watchdog = Runnable { }
    )

    private fun ttsBoundary() = LatencyBoundary(
        metric = LatencyMetric.TTS_REQUEST_TO_START,
        startedAtNanos = 1_000_000L
    )

    private fun latencyEntry(
        id: String,
        boundary: LatencyBoundary
    ) = UtteranceRegistry.Entry(
        id = id,
        onStart = { endedAtNanos -> boundary.recordAt(endedAtNanos, "started", "id" to id) },
        onComplete = { },
        watchdog = Runnable { },
        onStartMissing = { outcome ->
            boundary.recordAt(2_000_000L, outcome, "id" to id)
        }
    )
}
