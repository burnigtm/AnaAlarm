package com.anaalarm.ai.stream

/**
 * Splits accumulated visible model output into speakable phrases while streaming.
 *
 * Tuning rules from docs/RELIABILITY_AND_LATENCY.md (constants, not provider semantics):
 * - Emit at the last strong sentence boundary once at least [sentenceMinChars] are buffered.
 * - Otherwise emit at the last clause boundary once at least [clauseMinChars] are buffered.
 * - Otherwise, when the buffer reaches [hardLimitChars], emit up to the last whitespace inside
 *   the limit (or exactly at the limit when no whitespace exists) so growth stays bounded.
 * - A split never divides a Unicode surrogate pair; decimal points inside numbers are not
 *   treated as sentence boundaries.
 * - The unfinished tail stays buffered and is returned by [flush] on stream completion.
 */
internal class PhraseSegmenter(
    private val sentenceMinChars: Int = DEFAULT_SENTENCE_MIN_CHARS,
    private val clauseMinChars: Int = DEFAULT_CLAUSE_MIN_CHARS,
    private val hardLimitChars: Int = DEFAULT_HARD_LIMIT_CHARS
) {
    private val buffer = StringBuilder()

    /** Consumes one text delta and returns every phrase that became ready to speak. */
    fun feed(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        val phrases = ArrayList<String>(1)
        while (true) {
            val cut = findCut() ?: break
            takePhrase(cut)?.let(phrases::add)
        }
        return phrases
    }

    /** Returns and clears any unterminated tail; blank tails produce an empty string. */
    fun flush(): String {
        if (buffer.isBlank()) {
            buffer.clear()
            return ""
        }
        return takePhrase(buffer.length) ?: ""
    }

    val bufferedChars: Int get() = buffer.length

    /**
     * Returns the exclusive end index of the next phrase, or null when nothing may be emitted.
     */
    private fun findCut(): Int? {
        val length = buffer.length
        if (length >= hardLimitChars) {
            val whitespaceCut = lastWhitespaceCutAtOrBefore(hardLimitChars)
            return surrogateSafe(whitespaceCut ?: hardLimitChars)
        }
        if (length >= sentenceMinChars) {
            lastBoundaryAfter(SENTENCE_TERMINATORS, skipDecimal = true)?.let { return it }
        }
        if (length >= clauseMinChars) {
            lastBoundaryAfter(CLAUSE_TERMINATORS, skipDecimal = false)?.let { return it }
        }
        return null
    }

    /** Exclusive cut index right after the final boundary character in the whole buffer. */
    private fun lastBoundaryAfter(terminators: Set<Char>, skipDecimal: Boolean): Int? {
        for (index in buffer.length - 1 downTo 1) {
            if (buffer[index] !in terminators) continue
            if (skipDecimal && isDecimalPoint(index)) continue
            return swallowTrailingSpace(index + 1)
        }
        return null
    }

    private fun isDecimalPoint(boundaryIndex: Int): Boolean =
        buffer[boundaryIndex] == '.' &&
            boundaryIndex > 0 &&
            boundaryIndex + 1 < buffer.length &&
            buffer[boundaryIndex - 1].isDigit() &&
            buffer[boundaryIndex + 1].isDigit()

    /** Includes following whitespace in the emitted phrase so the tail starts clean. */
    private fun swallowTrailingSpace(afterBoundary: Int): Int {
        var end = afterBoundary
        while (end < buffer.length && buffer[end].isWhitespace()) end++
        return end
    }

    private fun lastWhitespaceCutAtOrBefore(limit: Int): Int? {
        val scanEnd = minOf(limit, buffer.length)
        for (index in scanEnd - 1 downTo 0) {
            // Cut after the whitespace so the remainder never starts with a stray space.
            if (buffer[index].isWhitespace()) return index + 1
        }
        return null
    }

    /** Steps back off a low surrogate so a cut never splits a code point in half. */
    private fun surrogateSafe(position: Int): Int {
        if (position <= 0 || position >= buffer.length) return position
        if (Character.isHighSurrogate(buffer[position - 1]) && Character.isLowSurrogate(buffer[position])) {
            return position - 1
        }
        return position
    }

    private fun takePhrase(endExclusive: Int): String? {
        if (endExclusive <= 0) return null
        val raw = buffer.substring(0, endExclusive)
        buffer.delete(0, endExclusive)
        // Collapse whitespace runs for speech without rewriting what was already consumed:
        // boundaries were found on the original accumulation.
        val phrase = WHITESPACE_RUNS.replace(raw, " ").trim()
        return phrase.ifEmpty { null }
    }

    companion object {
        const val DEFAULT_SENTENCE_MIN_CHARS = 24
        const val DEFAULT_CLAUSE_MIN_CHARS = 72
        const val DEFAULT_HARD_LIMIT_CHARS = 160

        private val SENTENCE_TERMINATORS = setOf('.', '!', '?', '…')
        private val CLAUSE_TERMINATORS = setOf(',', ';', ':')
        private val WHITESPACE_RUNS = Regex("\\s+")
    }
}
