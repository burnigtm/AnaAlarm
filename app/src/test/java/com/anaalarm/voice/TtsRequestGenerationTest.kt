package com.anaalarm.voice

import com.anaalarm.telemetry.LatencyBoundary
import com.anaalarm.telemetry.LatencyEvent
import com.anaalarm.telemetry.LatencyEventSink
import com.anaalarm.telemetry.LatencyMetric
import com.anaalarm.telemetry.LatencyMetrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRequestGenerationTest {

    @Test
    fun stopInvalidatesSpeechThatWasQueuedBeforeIt() {
        val generation = TtsRequestGeneration()
        val queuedBeforeStop = generation.current()

        generation.invalidate()

        assertFalse(generation.isCurrent(queuedBeforeStop))
        assertTrue(generation.isCurrent(generation.current()))
    }

    @Test
    fun stoppedQueuedRequestEmitsOneCancelledBoundaryAndRejectsLateStart() = runBlocking {
        val generation = TtsRequestGeneration()
        val queuedBeforeStop = generation.current()
        val events = mutableListOf<LatencyEvent>()
        val boundary = LatencyBoundary(
            metric = LatencyMetric.TTS_REQUEST_TO_START,
            startedAtNanos = 1_000_000L
        )

        LatencyMetrics.withEventSink(LatencyEventSink { events.add(it) }) {
            generation.invalidate()
            if (!generation.isCurrent(queuedBeforeStop)) {
                boundary.recordAt(2_000_000L, "cancelled")
            }
            // Simulates a main-loop start racing after Stop; the boundary must remain terminal.
            boundary.recordAt(3_000_000L, "started")
        }

        assertEquals(1, events.size)
        assertEquals("cancelled", events.single().outcome)
    }
}
