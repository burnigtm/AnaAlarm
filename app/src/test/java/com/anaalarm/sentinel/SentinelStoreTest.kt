package com.anaalarm.sentinel

import com.anaalarm.telemetry.LatencyEvent
import com.anaalarm.telemetry.LatencyMetric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SentinelStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun close() { scope.cancel(); SentinelEvents.recorder = null }

    @Test fun historyIsBoundedAndExpiresAfterSevenDays() = runBlocking {
        var now = SentinelStore.RETENTION_MILLIS * 2
        val store = SentinelStore(directory.newFile(), scope) { now }
        repeat(210) {
            store.record(EventCode.ALARM_SCHEDULED, EventOutcome.COMPLETED, null)
            store.snapshot()
        }
        assertEquals(200, store.snapshot().size)
        now += SentinelStore.RETENTION_MILLIS + 1
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun corruptStorageRecoversAndClearSurvivesReopening() = runBlocking {
        val file = directory.newFile().apply { writeText("not json") }
        val store = SentinelStore(file, scope) { 100 }
        assertTrue(store.snapshot().isEmpty())
        store.record(EventCode.TTS_START, EventOutcome.FAILED, -1)
        assertEquals(0L, store.snapshot().single().durationMillis)
        val reopened = SentinelStore(file, scope) { 100 }
        assertEquals(1, reopened.snapshot().size)
        store.clear()
        assertTrue(SentinelStore(file, scope) { 100 }.snapshot().isEmpty())
    }

    @Test fun storageFailureDoesNotBlockRecorderOrReads() = runBlocking {
        val parent = directory.newFile()
        val store = SentinelStore(java.io.File(parent, "events.json"), scope) { 100 }
        store.record(EventCode.ALARM_PLAYBACK_FAILED, EventOutcome.FAILED, null)
        assertEquals(1, store.snapshot().size)
    }

    @Test fun futureAndExpiredRecordsAreRemoved() = runBlocking {
        val file = directory.newFile()
        val now = SentinelStore.RETENTION_MILLIS * 2
        file.writeText(Json.encodeToString(listOf(
            SentinelEvent(0, EventCode.TTS_START, EventOutcome.STARTED),
            SentinelEvent(now + 1, EventCode.TTS_START, EventOutcome.STARTED),
            SentinelEvent(now, EventCode.TTS_START, EventOutcome.STARTED))))
        assertEquals(listOf(now), SentinelStore(file, scope) { now }.snapshot().map { it.timestamp })
    }

    @Test fun latencyDropsAllFreeTextAndNormalizesKnownVoiceFailures() {
        val recorded = mutableListOf<Triple<EventCode, EventOutcome, Long?>>()
        SentinelEvents.recorder = SentinelEventRecorder { code, outcome, duration -> recorded += Triple(code, outcome, duration) }
        SentinelEvents.latency(LatencyEvent(LatencyMetric.TTS_REQUEST_TO_START, 42, "engine_failed",
            mapOf("api_key" to "secret", "transcript" to "private conversation")))
        assertEquals(Triple(EventCode.TTS_START, EventOutcome.FAILED, 42L), recorded.single())
        SentinelEvents.latency(LatencyEvent(LatencyMetric.MODEL_REQUEST_TO_COMPLETION, 7, "private secret"))
        assertEquals(EventOutcome.UNKNOWN, recorded.last().second)
    }

    @Test fun recorderFailureNeverEscapesIntoAlarmOrVoiceCallbacks() {
        SentinelEvents.recorder = SentinelEventRecorder { _, _, _ -> error("disk failure") }
        SentinelEvents.record(EventCode.ALARM_SCHEDULED, EventOutcome.COMPLETED)
        SentinelEvents.latency(LatencyEvent(LatencyMetric.ASR_START_TO_FINAL, 1, "error"))
    }

    @Test fun anotherAppsNextAlarmIsNeverReportedAsOurs() {
        val other = nextAlarmHealth("com.anaalarm.internal", "other.clock", 123)
        assertEquals(HealthStatus.UNAVAILABLE, other.status)
        assertEquals(HealthDetail.OTHER_APP_NEXT, other.detail)
        assertNull(other.value)
        assertEquals(HealthDetail.NO_NEXT_ALARM, nextAlarmHealth("com.anaalarm", null, null).detail)
        assertEquals(123L, nextAlarmHealth("com.anaalarm", "com.anaalarm", 123).value)
    }
}
