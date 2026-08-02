package com.anaalarm.voice

import com.anaalarm.telemetry.LatencyEvent
import com.anaalarm.telemetry.LatencyEventSink
import com.anaalarm.telemetry.LatencyMetric
import com.anaalarm.telemetry.LatencyMetrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionGenerationTest {

    @Test
    fun onlyTheCurrentRecognitionCanComplete() {
        val generations = RecognitionGeneration()
        val first = generations.next()
        val second = generations.next()

        assertFalse(generations.complete(first))
        assertTrue(generations.isCurrent(second))
        assertTrue(generations.complete(second))
        assertFalse(generations.complete(second))
    }

    @Test
    fun invalidationRejectsLateCallbacks() {
        val generations = RecognitionGeneration()
        val cancelled = generations.next()

        generations.invalidate()

        assertFalse(generations.isCurrent(cancelled))
        assertFalse(generations.complete(cancelled))
    }

    @Test
    fun fallbackEngineGenerationRejectsTheRetiredEngine() {
        val generations = RecognitionGeneration()
        val onDevice = generations.next()
        val defaultRecognizer = generations.next()

        assertFalse(generations.complete(onDevice))
        assertTrue(generations.isCurrent(defaultRecognizer))
        assertTrue(generations.complete(defaultRecognizer))
    }

    @Test
    fun staleAndDuplicateCallbacksCannotEmitASecondAsrMetric() = runBlocking {
        val generations = RecognitionGeneration()
        val events = mutableListOf<LatencyEvent>()
        val stale = generations.next()
        val current = generations.next()

        LatencyMetrics.withEventSink(LatencyEventSink { events.add(it) }) {
            recordIfComplete(generations, stale, "stale_result")
            recordIfComplete(generations, current, "result")
            recordIfComplete(generations, current, "duplicate_result")
        }

        assertEquals(1, events.size)
        assertEquals("result", events.single().outcome)
    }

    private fun recordIfComplete(
        generations: RecognitionGeneration,
        generation: Long,
        outcome: String
    ) {
        if (!generations.complete(generation)) return
        LatencyMetrics.recordAt(
            metric = LatencyMetric.ASR_START_TO_FINAL,
            startedAtNanos = 1_000_000L,
            endedAtNanos = 2_000_000L,
            outcome = outcome
        )
    }
}
