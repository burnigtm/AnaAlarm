package com.anaalarm.ai.stream

import com.anaalarm.ai.ApiException
import com.anaalarm.voice.TurnSpeaker
import com.anaalarm.voice.TtsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the spoken delivery of one streamed model turn.
 *
 * Responsibilities (docs/RELIABILITY_AND_LATENCY.md):
 * - Feeds visible text deltas through a [PhraseSegmenter] and speaks each phrase over TTS.
 * - The first audible phrase flushes whatever was playing (legacy [TtsManager.speak] path);
 *   every later phrase appends via QUEUE_ADD ([TtsManager.speakQueued]) so earlier audio is
 *   never cut mid-sentence.
 * - Applies bounded backpressure: at most [MAX_OUTSTANDING_PHRASES] phrases may be outstanding;
 *   when full, the network collector suspends instead of growing the platform queue.
 * - The turn settles only after the semantic terminal was observed AND every registered
 *   utterance completed (engine watchdogs guarantee completion callbacks even when stuck).
 * - A terminal incomplete/failed event stops unsaid phrases, reports one typed failure, and
 *   marks the turn so it can never be persisted as a completed assistant turn.
 * - Non-streaming fallback is allowed only before any phrase became audible.
 *
 * One instance belongs to exactly one turn; cross-turn isolation comes from the controller
 * creating a fresh coordinator per turn. All entry points are safe to call from any thread.
 */
internal class StreamingTurnCoordinator(
    private val turnId: Long,
    private val ttsManager: TurnSpeaker,
    private val segmenter: PhraseSegmenter = PhraseSegmenter(),
    private val onFirstPhraseAudioStarted: () -> Unit = {}
) {
    private val lock = Any()
    private val pendingCompletions = TreeMap<Int, CompletableDeferred<Unit>>()
    private val settled = CompletableDeferred<Unit>()

    private var lastSequence = 0
    private var disposed = false
    private var terminalSeen = false
    private var firstAudioStarted = false
    private var reportedFailure: ApiException? = null

    /** Sink for visible model text; called in order by the stream collector. */
    suspend fun accept(delta: String) {
        segmenter.feed(delta).forEach { phrase ->
            if (phrase.isBlank()) return@forEach
            awaitOutstandingCapacity()
            enqueue(phrase)
        }
    }

    /** Informs the coordinator that the model stream reached response.completed normally. */
    fun markTerminalCompleted() {
        synchronized(lock) {
            terminalSeen = true
            settleIfIdleLocked()
        }
    }

    /**
     * Terminal incomplete/failed: stops unsaid phrases, records the typed failure, and settles
     * the turn so the controller can proceed to its error path without waiting on audio.
     */
    fun markTerminalFailed(error: ApiException) {
        synchronized(lock) {
            if (reportedFailure == null) reportedFailure = error
            terminalSeen = true
            disposed = true
            drainCompletionsLocked()
        }
        ttsManager.cancelQueuedTurn(turnId)
        settled.complete(Unit)
    }

    /**
     * True only before any phrase became audible. After first audio the already-spoken text
     * cannot be invisibly retried, so failures surface through the normal error path.
     */
    fun canFallbackToNonStreaming(): Boolean =
        synchronized(lock) { !firstAudioStarted && reportedFailure == null }

    /** The typed failure carried by an incomplete/failed terminal, if any. */
    val failure: ApiException?
        get() = synchronized(lock) { reportedFailure }

    val phrasesEnqueued: Int
        get() = synchronized(lock) { lastSequence }

    /**
     * Waits until the terminal plus every utterance completion arrived (or a safety timeout
     * fired — engine watchdogs make completion callbacks guaranteed, so this is belt-and-braces).
     */
    suspend fun awaitSettled(timeoutMillis: Long = DEFAULT_SETTLE_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMillis) { settled.await() } != null

    /** Cancels unsaid audio and releases all waiters. Idempotent; safe after any outcome. */
    fun dispose() {
        val hadPending: Boolean
        synchronized(lock) {
            hadPending = pendingCompletions.isNotEmpty()
            disposed = true
            terminalSeen = true
            drainCompletionsLocked()
        }
        if (hadPending) ttsManager.cancelQueuedTurn(turnId)
        settled.complete(Unit)
    }

    private suspend fun awaitOutstandingCapacity() {
        while (true) {
            val oldest = synchronized(lock) {
                if (disposed || pendingCompletions.size < MAX_OUTSTANDING_PHRASES) {
                    null
                } else {
                    pendingCompletions.firstEntry().value
                }
            }
            if (oldest == null) return
            oldest.await()
        }
    }

    private fun enqueue(phrase: String) {
        val sequence = synchronized(lock) {
            if (disposed) return
            ++lastSequence
            pendingCompletions[lastSequence] = CompletableDeferred()
            lastSequence
        }
        ttsManager.speakQueued(
            text = phrase,
            turnId = turnId,
            sequence = sequence,
            // QUEUE_FLUSH on the turn's first phrase replaces pre-turn audio; later phrases
            // append so earlier speech is never cut mid-sentence.
            flush = sequence == 1,
            onStart = ::onPhraseStarted,
            completion = { onPhraseCompleted(sequence) }
        )
    }

    private fun onPhraseStarted() {
        val announceFirst: Boolean = synchronized(lock) {
            val first = !firstAudioStarted
            firstAudioStarted = true
            first
        }
        if (announceFirst) onFirstPhraseAudioStarted()
    }

    private fun onPhraseCompleted(sequence: Int) {
        val deferred = synchronized(lock) { pendingCompletions.remove(sequence) } ?: return
        deferred.complete(Unit)
        synchronized(lock) { settleIfIdleLocked() }
    }

    private fun drainCompletionsLocked() {
        pendingCompletions.values.forEach { it.complete(Unit) }
        pendingCompletions.clear()
    }

    private fun settleIfIdleLocked() {
        if (terminalSeen && pendingCompletions.isEmpty() && !settled.isCompleted) {
            settled.complete(Unit)
        }
    }

    companion object {
        const val MAX_OUTSTANDING_PHRASES = 3
        const val DEFAULT_SETTLE_TIMEOUT_MS = 60_000L

        private val turnIds = AtomicLong(0L)

        /** Process-unique id for a new streaming turn's utterance ids. */
        fun nextTurnId(): Long = turnIds.incrementAndGet()
    }
}
