package com.anaalarm.telemetry

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyMetricsTest {

    @Test
    fun wireNamesCoverTheUserVisibleLatencySpans() {
        assertEquals(
            listOf(
                "alarm_to_first_audio",
                "asr_start_to_final",
                "model_request_to_completion",
                "model_request_to_first_text",
                "tts_request_to_start"
            ),
            LatencyMetric.entries.map { it.wireName }
        )
    }

    @Test
    fun durationUsesMonotonicNanosecondsAndTruncatesToMilliseconds() {
        val started = 4_000_000_000L

        assertEquals(12L, LatencyMetrics.durationMillis(started, started + 12_999_999L))
    }

    @Test
    fun durationClampsMissingOrReversedBoundaries() {
        assertEquals(0L, LatencyMetrics.durationMillis(0L, 2_000_000L))
        assertEquals(0L, LatencyMetrics.durationMillis(3_000_000L, 2_000_000L))
        assertEquals(0L, LatencyMetrics.durationMillis(3_000_000L, 3_000_000L))
    }

    @Test
    fun formatIsStableMetadataOnlyAndParserSafe() {
        val event = LatencyEvent(
            metric = LatencyMetric.MODEL_REQUEST_TO_COMPLETION,
            durationMillis = 12L,
            outcome = "completed ok",
            dimensions = linkedMapOf(
                "status" to "done",
                "request id" to "7"
            )
        )

        assertEquals(
            "event=latency metric=model_request_to_completion duration_ms=12 " +
                "outcome=completed_ok request_id=7 status=done",
            LatencyMetrics.format(event)
        )
    }

    @Test
    fun formatBoundsValuesAndDoesNotEmitWhitespaceOrControlCharacters() {
        val event = LatencyEvent(
            metric = LatencyMetric.ASR_START_TO_FINAL,
            durationMillis = -4L,
            outcome = "\n",
            dimensions = mapOf("bad key" to "${"x".repeat(80)}\r\nprivate text")
        )
        val formatted = LatencyMetrics.format(event)

        assertEquals(
            "event=latency metric=asr_start_to_final duration_ms=0 outcome=unknown " +
                "bad_key=${"x".repeat(64)}",
            formatted
        )
    }

    @Test
    fun eventSinkIsCallScopedAndRestoresThePreviousSink() = runBlocking {
        val outer = mutableListOf<LatencyEvent>()
        val inner = mutableListOf<LatencyEvent>()

        LatencyMetrics.withEventSink(LatencyEventSink { outer.add(it) }) {
            recordAt(outcome = "outer_before")
            LatencyMetrics.withEventSink(LatencyEventSink { inner.add(it) }) {
                recordAt(outcome = "inner")
            }
            recordAt(outcome = "outer_after")
        }
        recordAt(outcome = "outside")

        assertEquals(listOf("outer_before", "outer_after"), outer.map { it.outcome })
        assertEquals(listOf("inner"), inner.map { it.outcome })
    }

    @Test
    fun latencyBoundaryEmitsOnlyItsFirstTerminalOutcome() = runBlocking {
        val events = mutableListOf<LatencyEvent>()
        val boundary = LatencyBoundary(
            metric = LatencyMetric.TTS_REQUEST_TO_START,
            startedAtNanos = 1_000_000L
        )

        LatencyMetrics.withEventSink(LatencyEventSink { events.add(it) }) {
            assertTrue(boundary.recordAt(2_000_000L, "superseded"))
            assertFalse(boundary.recordAt(3_000_000L, "cancelled"))
            assertFalse(boundary.recordAt(4_000_000L, "started"))
        }

        assertEquals(1, events.size)
        assertEquals("superseded", events.single().outcome)
    }

    @Test
    fun alarmBoundaryRecordsOnlyTheFirstCompetingAudioPath() = runBlocking {
        val events = mutableListOf<LatencyEvent>()
        val boundary = LatencyBoundary(
            metric = LatencyMetric.ALARM_TO_FIRST_AUDIO,
            startedAtNanos = 1_000_000L
        )

        LatencyMetrics.withEventSink(LatencyEventSink { events.add(it) }) {
            assertTrue(boundary.recordAt(2_000_000L, "started", "audio_path" to "ringtone"))
            assertFalse(
                boundary.recordAt(3_000_000L, "started", "audio_path" to "generated_tone")
            )
        }

        assertEquals(1, events.size)
        assertEquals("ringtone", events.single().dimensions["audio_path"])
    }

    private fun recordAt(outcome: String) {
        LatencyMetrics.recordAt(
            metric = LatencyMetric.MODEL_REQUEST_TO_COMPLETION,
            startedAtNanos = 1_000_000L,
            endedAtNanos = 2_000_000L,
            outcome = outcome
        )
    }
}
