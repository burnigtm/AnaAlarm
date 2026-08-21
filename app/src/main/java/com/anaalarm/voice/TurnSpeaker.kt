package com.anaalarm.voice

/**
 * Speaking surface used by the streaming turn coordinator.
 *
 * Exists so the QUEUE_ADD/backpressure/settle contract can be exercised by fast JVM tests
 * against a fake implementation; production binding is [TtsManager].
 */
internal interface TurnSpeaker {
    /**
     * Speaks one phrase as part of a streaming turn. [flush] marks the turn's first audible
     * phrase: QUEUE_FLUSH replaces whatever played before; all later phrases append (QUEUE_ADD)
     * so earlier audio is never cut mid-sentence.
     */
    fun speakQueued(
        text: String,
        turnId: Long,
        sequence: Int,
        flush: Boolean = false,
        onStart: () -> Unit = {},
        completion: () -> Unit = {}
    )

    /** Cancels every unsaid phrase of one streaming turn; already-audible speech is untouched. */
    fun cancelQueuedTurn(turnId: Long)
}
