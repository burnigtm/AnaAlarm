package com.anaalarm.sentinel

import com.anaalarm.telemetry.LatencyEvent
import com.anaalarm.telemetry.LatencyMetric
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
internal enum class EventCode {
    ALARM_SCHEDULED, ALARM_SCHEDULE_FAILED, ALARM_PLAYBACK_FAILED,
    ALARM_TO_AUDIO, ASR_RESULT, MODEL_COMPLETION, MODEL_FIRST_TEXT, TTS_START
}

@Serializable
internal enum class EventOutcome {
    STARTED, COMPLETED, RESULT, FAILED, ERROR, CANCELLED, TIMED_OUT, UNKNOWN
}

/** No free-text fields: neither an exception nor a transcript can enter the report. */
@Serializable
internal data class SentinelEvent(
    val timestamp: Long,
    val code: EventCode,
    val outcome: EventOutcome,
    val durationMillis: Long? = null
)

internal fun interface SentinelEventRecorder {
    fun record(code: EventCode, outcome: EventOutcome, durationMillis: Long?)
}

internal interface SentinelHistory : SentinelEventRecorder {
    suspend fun snapshot(): List<SentinelEvent>
    suspend fun clear()
}

/** Bounded actor: alarm/voice callbacks never wait for disk or an available queue slot. */
internal class SentinelStore(
    private val file: File,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) : SentinelHistory {
    private sealed interface Command {
        data class Add(val event: SentinelEvent) : Command
        data class Read(val reply: CompletableDeferred<List<SentinelEvent>>) : Command
        data class Clear(val reply: CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(MAX_EVENTS)

    init {
        scope.launch {
            val events = runCatching {
                if (!file.exists() || file.length() > MAX_BYTES) emptyList()
                else Json.decodeFromString<List<SentinelEvent>>(file.readText())
            }.getOrDefault(emptyList()).toMutableList()
            prune(events)
            persist(events)
            for (command in commands) {
                when (command) {
                    is Command.Add -> {
                        events.add(command.event)
                        prune(events)
                        persist(events)
                    }
                    is Command.Read -> {
                        prune(events)
                        persist(events)
                        command.reply.complete(events.toList())
                    }
                    is Command.Clear -> {
                        events.clear()
                        persist(events).fold(
                            onSuccess = { command.reply.complete(Unit) },
                            onFailure = { command.reply.completeExceptionally(it) }
                        )
                    }
                }
            }
        }
    }

    override fun record(code: EventCode, outcome: EventOutcome, durationMillis: Long?) {
        runCatching {
            commands.trySend(Command.Add(SentinelEvent(clock(), code, outcome, durationMillis?.coerceAtLeast(0))))
        }
    }

    override suspend fun snapshot(): List<SentinelEvent> {
        val reply = CompletableDeferred<List<SentinelEvent>>()
        commands.send(Command.Read(reply))
        return reply.await()
    }

    override suspend fun clear() {
        val reply = CompletableDeferred<Unit>()
        commands.send(Command.Clear(reply))
        reply.await()
    }

    private fun prune(events: MutableList<SentinelEvent>) {
        val now = clock()
        events.removeAll { it.timestamp < now - RETENTION_MILLIS || it.timestamp > now }
        if (events.size > MAX_EVENTS) events.subList(0, events.size - MAX_EVENTS).clear()
    }

    private fun persist(events: List<SentinelEvent>): Result<Unit> =
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(Json.encodeToString(events))
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
            Unit
        }

    companion object {
        const val MAX_EVENTS = 200
        const val RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_BYTES = 128 * 1024L
    }
}

/** No recorder is installed until credential storage is available after unlock. */
internal object SentinelEvents {
    @Volatile var recorder: SentinelEventRecorder? = null

    fun record(code: EventCode, outcome: EventOutcome, durationMillis: Long? = null) {
        runCatching { recorder?.record(code, outcome, durationMillis) }
    }

    fun latency(event: LatencyEvent) {
        val code = when (event.metric) {
            LatencyMetric.ALARM_TO_FIRST_AUDIO -> EventCode.ALARM_TO_AUDIO
            LatencyMetric.ASR_START_TO_FINAL -> EventCode.ASR_RESULT
            LatencyMetric.MODEL_REQUEST_TO_COMPLETION -> EventCode.MODEL_COMPLETION
            LatencyMetric.MODEL_REQUEST_TO_FIRST_TEXT -> EventCode.MODEL_FIRST_TEXT
            LatencyMetric.TTS_REQUEST_TO_START -> EventCode.TTS_START
        }
        val outcome = when (event.outcome) {
            "engine_failed", "engine_error", "enqueue_failed" -> EventOutcome.FAILED
            "watchdog" -> EventOutcome.TIMED_OUT
            "stopped" -> EventOutcome.CANCELLED
            else -> EventOutcome.entries.firstOrNull { it.name.equals(event.outcome, ignoreCase = true) }
                ?: EventOutcome.UNKNOWN
        }
        // Deliberately discard every dimension, including arbitrary user-supplied strings.
        record(code, outcome, event.durationMillis)
    }
}
