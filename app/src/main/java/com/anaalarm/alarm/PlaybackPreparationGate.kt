package com.anaalarm.alarm

/**
 * Owns the resource produced by an asynchronous playback-preparation attempt.
 *
 * The gate itself is intentionally platform-free so the stop/restart ordering contract can be
 * covered by fast JVM tests. Callers must invoke its methods from one owner thread; background
 * work only carries the immutable [Token] back to that thread.
 */
internal class PlaybackPreparationGate<T : Any>(
    private val release: (T) -> Unit
) {
    data class Token internal constructor(
        val generation: Long,
        val alarmId: Long
    )

    private var generation = 0L
    private var currentToken: Token? = null
    private var attachment: T? = null

    /** Starts a new preparation generation and retires any resource owned by the old one. */
    fun begin(alarmId: Long): Token {
        val token = Token(generation = ++generation, alarmId = alarmId)
        currentToken = token
        releaseAttachment()
        return token
    }

    /**
     * Attaches [candidate] only when [token] still identifies the active alarm generation.
     * A rejected or replaced resource is released before this method returns.
     */
    fun attach(token: Token, candidate: T): Boolean {
        if (currentToken != token) {
            release(candidate)
            return false
        }

        if (attachment === candidate) return true
        releaseAttachment()

        // A release hook may synchronously invalidate the gate. Never install into a retired
        // generation even in that re-entrant case.
        if (currentToken != token) {
            release(candidate)
            return false
        }

        attachment = candidate
        return true
    }

    fun owns(token: Token, candidate: T): Boolean =
        currentToken == token && attachment === candidate

    /** Releases [candidate] only if it is the resource currently owned by [token]. */
    fun releaseIfOwned(token: Token, candidate: T): Boolean {
        if (!owns(token, candidate)) return false
        attachment = null
        release(candidate)
        return true
    }

    /** Invalidates in-flight preparation and releases the currently attached resource. */
    fun invalidate() {
        generation++
        currentToken = null
        releaseAttachment()
    }

    fun hasAttachment(): Boolean = attachment != null

    private fun releaseAttachment() {
        val previous = attachment ?: return
        // Clear ownership first so callbacks caused by release cannot observe a live attachment.
        attachment = null
        release(previous)
    }
}
