package com.anaalarm.telemetry

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Stable names consumed by logcat/benchmark tooling. Do not put user content in dimensions. */
internal enum class LatencyMetric(val wireName: String) {
    ALARM_TO_FIRST_AUDIO("alarm_to_first_audio"),
    ASR_START_TO_FINAL("asr_start_to_final"),
    MODEL_REQUEST_TO_COMPLETION("model_request_to_completion"),
    MODEL_REQUEST_TO_FIRST_TEXT("model_request_to_first_text"),
    TTS_REQUEST_TO_START("tts_request_to_start")
}

internal data class LatencyEvent(
    val metric: LatencyMetric,
    val durationMillis: Long,
    val outcome: String,
    val dimensions: Map<String, String> = emptyMap()
)

/** Receives structured latency events without replacing the production logcat emission. */
internal fun interface LatencyEventSink {
    fun emit(event: LatencyEvent)
}

/**
 * Emits one-line, metadata-only latency events using Android's monotonic elapsed-realtime clock.
 *
 * Schema: `event=latency metric=<name> duration_ms=<n> outcome=<value> key=value ...`.
 * Dimension keys are sorted, and values are bounded/sanitised so log parsers see stable records.
 */
internal object LatencyMetrics {
    private const val TAG = "AnaLatency"
    private const val MAX_VALUE_LENGTH = 64
    private val scopedSink = ThreadLocal<LatencyEventSink?>()

    fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()

    fun record(
        metric: LatencyMetric,
        startedAtNanos: Long,
        outcome: String,
        vararg dimensions: Pair<String, Any?>
    ) = recordAt(metric, startedAtNanos, nowNanos(), outcome, *dimensions)

    fun recordAt(
        metric: LatencyMetric,
        startedAtNanos: Long,
        endedAtNanos: Long,
        outcome: String,
        vararg dimensions: Pair<String, Any?>
    ) {
        val event = LatencyEvent(
            metric = metric,
            durationMillis = durationMillis(startedAtNanos, endedAtNanos),
            outcome = outcome,
            dimensions = dimensions.associate { (key, value) -> key to value.toString() }
        )
        Log.i(TAG, format(event))
        scopedSink.get()?.emit(event)
    }

    /**
     * Observes events emitted by [block] and its child coroutines. The prior sink is restored on
     * success, failure, or cancellation, so JVM tests do not share recorder state.
     */
    internal suspend fun <T> withEventSink(
        sink: LatencyEventSink,
        block: suspend CoroutineScope.() -> T
    ): T = withContext(scopedSink.asContextElement(sink)) { block() }

    internal fun durationMillis(startedAtNanos: Long, endedAtNanos: Long): Long {
        if (startedAtNanos <= 0L || endedAtNanos <= startedAtNanos) return 0L
        return TimeUnit.NANOSECONDS.toMillis(endedAtNanos - startedAtNanos)
    }

    internal fun format(event: LatencyEvent): String = buildString {
        append("event=latency")
        append(" metric=").append(event.metric.wireName)
        append(" duration_ms=").append(event.durationMillis.coerceAtLeast(0L))
        append(" outcome=").append(safeToken(event.outcome))
        event.dimensions.toSortedMap().forEach { (key, value) ->
            append(' ')
            append(safeKey(key))
            append('=')
            append(safeToken(value))
        }
    }

    private fun safeKey(value: String): String {
        val normalized = value
            .lowercase(Locale.US)
            .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .take(MAX_VALUE_LENGTH)
        return normalized.ifBlank { "dimension" }
    }

    private fun safeToken(value: String): String {
        val normalized = value
            .trim()
            .map { character ->
                if (character.isLetterOrDigit() || character in "._:-") character else '_'
            }
            .joinToString("")
            .take(MAX_VALUE_LENGTH)
        return normalized.ifBlank { "unknown" }
    }
}

/**
 * Atomic exactly-once gate for a single latency span's terminal event.
 *
 * Different completion paths may race (for example timeout versus a late callback). The first
 * terminal path records the event; every later path is a no-op.
 */
internal class LatencyBoundary(
    private val metric: LatencyMetric,
    private val startedAtNanos: Long
) {
    private val recorded = AtomicBoolean(false)

    fun record(
        outcome: String,
        vararg dimensions: Pair<String, Any?>
    ): Boolean = recordAt(LatencyMetrics.nowNanos(), outcome, *dimensions)

    fun recordAt(
        endedAtNanos: Long,
        outcome: String,
        vararg dimensions: Pair<String, Any?>
    ): Boolean {
        if (!recorded.compareAndSet(false, true)) return false
        LatencyMetrics.recordAt(metric, startedAtNanos, endedAtNanos, outcome, *dimensions)
        return true
    }
}
