package com.anaalarm.ai.stream

import com.anaalarm.ai.ApiException
import com.anaalarm.voice.TurnSpeaker
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTurnCoordinatorTest {

    /** Deterministic speaker double: records calls, fires callbacks only when told to. */
    private class FakeSpeaker : TurnSpeaker {
        data class Call(
            val text: String,
            val turnId: Long,
            val sequence: Int,
            val flush: Boolean
        )

        val calls = mutableListOf<Call>()
        val cancelledTurns = mutableListOf<Long>()
        private val pendingStarts = mutableMapOf<Int, () -> Unit>()
        private val pendingCompletions = mutableMapOf<Int, () -> Unit>()

        override fun speakQueued(
            text: String,
            turnId: Long,
            sequence: Int,
            flush: Boolean,
            onStart: () -> Unit,
            completion: () -> Unit
        ) {
            calls += Call(text, turnId, sequence, flush)
            pendingStarts[sequence] = onStart
            pendingCompletions[sequence] = completion
        }

        override fun cancelQueuedTurn(turnId: Long) {
            cancelledTurns += turnId
        }

        fun startPhrase(sequence: Int) {
            pendingStarts.remove(sequence)?.invoke()
        }

        fun completePhrase(sequence: Int) {
            pendingCompletions.remove(sequence)?.invoke()
        }

        fun outstandingAtSpeaker(): Int = pendingCompletions.size
    }

    private val speaker = FakeSpeaker()

    private fun coordinator(
        firstAudio: () -> Unit = {},
        segmenter: PhraseSegmenter = PhraseSegmenter()
    ) = StreamingTurnCoordinator(
        turnId = 77L,
        ttsManager = speaker,
        segmenter = segmenter,
        onFirstPhraseAudioStarted = firstAudio
    )

    /** One full sentence per delta so the segmenter emits exactly one phrase per accept. */
    private suspend fun StreamingTurnCoordinator.speakSentence(index: Int) {
        accept("Spoken phrase number $index ends here.")
    }

    @Test
    fun `first phrase flushes and later phrases append`() = runTest {
        val coordinator = coordinator()
        coordinator.speakSentence(1)
        coordinator.speakSentence(2)
        coordinator.speakSentence(3)

        assertEquals(3, speaker.calls.size)
        assertTrue(speaker.calls[0].flush)
        assertFalse(speaker.calls[1].flush)
        assertFalse(speaker.calls[2].flush)
        assertEquals(listOf(1, 2, 3), speaker.calls.map { it.sequence })
        assertEquals(
            listOf(
                "Spoken phrase number 1 ends here.",
                "Spoken phrase number 2 ends here.",
                "Spoken phrase number 3 ends here."
            ),
            speaker.calls.map { it.text }
        )
    }

    @Test
    fun `settlement requires the terminal and every utterance completion`() = runTest {
        val coordinator = coordinator()
        coordinator.speakSentence(1)
        coordinator.speakSentence(2)

        // All audio done but no terminal yet: must not settle.
        speaker.startPhrase(1)
        speaker.completePhrase(1)
        speaker.startPhrase(2)
        speaker.completePhrase(2)
        assertFalse(coordinator.awaitSettled(timeoutMillis = 50))

        coordinator.markTerminalCompleted()
        assertTrue(coordinator.awaitSettled(timeoutMillis = 100))
    }

    @Test
    fun `late and duplicate callbacks cannot break settlement`() = runTest {
        val coordinator = coordinator()
        coordinator.speakSentence(1)
        coordinator.speakSentence(2)
        coordinator.markTerminalCompleted()

        // Out-of-order completion arrives first.
        speaker.completePhrase(2)
        assertFalse(coordinator.awaitSettled(timeoutMillis = 50))

        speaker.completePhrase(1)
        assertTrue(coordinator.awaitSettled(timeoutMillis = 100))

        // Duplicates and unknown sequences are inert.
        speaker.completePhrase(1)
        speaker.completePhrase(99)
        assertTrue(coordinator.awaitSettled(timeoutMillis = 100))
    }

    @Test
    fun `outstanding phrases are bounded and apply backpressure`() = runTest {
        val coordinator = coordinator()
        val collector = async {
            coordinator.speakSentence(1)
            coordinator.speakSentence(2)
            coordinator.speakSentence(3)
            coordinator.speakSentence(4)
            coordinator.speakSentence(5)
        }
        repeat(5) { yield() }
        runCurrent()

        // Only MAX_OUTSTANDING_PHRASES may be enqueued while nothing completed.
        assertEquals(StreamingTurnCoordinator.MAX_OUTSTANDING_PHRASES, speaker.calls.size)

        speaker.startPhrase(1)
        speaker.completePhrase(1)
        repeat(3) { yield() }
        runCurrent()
        assertEquals(4, speaker.calls.size)

        // Each completion frees one slot; pump the scheduler so the collector can enqueue more.
        for (sequence in 2..5) {
            runCurrent()
            speaker.startPhrase(sequence)
            speaker.completePhrase(sequence)
            runCurrent()
        }
        collector.await()

        assertEquals(5, speaker.calls.size)
    }

    @Test
    fun `first audible phrase announces exactly once`() = runTest {
        var announcements = 0
        val coordinator = coordinator(firstAudio = { announcements++ })
        coordinator.speakSentence(1)
        coordinator.speakSentence(2)

        speaker.startPhrase(1)
        speaker.startPhrase(2)
        assertEquals(1, announcements)
    }

    @Test
    fun `fallback allowed before first audio and forbidden after`() = runTest {
        val coordinator = coordinator()
        assertTrue(coordinator.canFallbackToNonStreaming())

        coordinator.speakSentence(1)
        assertTrue(coordinator.canFallbackToNonStreaming())

        speaker.startPhrase(1)
        assertFalse(coordinator.canFallbackToNonStreaming())
    }

    @Test
    fun `failed terminal stops unsaid audio records typed failure and settles`() = runTest {
        val coordinator = coordinator()
        coordinator.speakSentence(1)
        coordinator.speakSentence(2)
        speaker.startPhrase(1)

        val failure = ApiException("incomplete response: max_output_tokens")
        coordinator.markTerminalFailed(failure)

        assertTrue(speaker.cancelledTurnContains(77L))
        assertEquals(failure, coordinator.failure)
        assertFalse(coordinator.canFallbackToNonStreaming())
        assertTrue(coordinator.awaitSettled(timeoutMillis = 100))

        // A completion racing the cancellation stays inert.
        speaker.completePhrase(2)
        assertTrue(coordinator.awaitSettled(timeoutMillis = 100))
    }

    private fun FakeSpeaker.cancelledTurnContains(turnId: Long) =
        cancelledTurns.contains(turnId)

    @Test
    fun `dispose cancels unsaid audio and releases waiters`() = runTest {
        val coordinator = coordinator()
        coordinator.speakSentence(1)
        coordinator.speakSentence(2)

        coordinator.dispose()

        assertTrue(speaker.cancelledTurnContains(77L))
        assertTrue(coordinator.awaitSettled(timeoutMillis = 100))

        // Deltas after disposal never reach the speaker.
        coordinator.speakSentence(3)
        assertEquals(2, speaker.calls.size)
    }

    @Test
    fun `whitespace-only deltas produce no speech`() = runTest {
        val coordinator = coordinator()
        coordinator.accept("   ")
        coordinator.accept("\n\t")
        assertEquals(0, speaker.calls.size)
        coordinator.markTerminalCompleted()
        assertTrue(coordinator.awaitSettled(timeoutMillis = 100))
    }

    @Test
    fun `turn ids are process-unique`() {
        val first = StreamingTurnCoordinator.nextTurnId()
        val second = StreamingTurnCoordinator.nextTurnId()
        assertTrue(second > first)
    }

    @Test
    fun `collector job survives until backpressure drains`() = runTest {
        val coordinator = coordinator()
        val done = launch {
            repeat(6) { index -> coordinator.speakSentence(index + 1) }
        }
        repeat(6) { yield() }
        // Drain everything; the collector must finish instead of hanging.
        for (sequence in 1..6) {
            speaker.startPhrase(sequence)
            speaker.completePhrase(sequence)
        }
        done.join()
        assertEquals(6, speaker.calls.size)
    }
}
