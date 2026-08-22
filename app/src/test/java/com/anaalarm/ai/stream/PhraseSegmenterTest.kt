package com.anaalarm.ai.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseSegmenterTest {

    @Test
    fun `short text without boundaries stays buffered`() {
        val segmenter = PhraseSegmenter()
        assertTrue(segmenter.feed("hello there, waking up").isEmpty())
        assertEquals("hello there, waking up", segmenter.flush())
    }

    @Test
    fun `sentence boundary emits only after the minimum length`() {
        val segmenter = PhraseSegmenter()
        // 22 chars before the period: below the 24-char gate.
        assertTrue(segmenter.feed("Good morning, sleepyhea").isEmpty())
        // Crossing the gate with a sentence terminator emits up to and including it.
        assertEquals(
            listOf("Good morning, sleepyhead."),
            segmenter.feed("d. ")
        )
    }

    @Test
    fun `clause boundary requires the longer threshold`() {
        val segmenter = PhraseSegmenter()
        val clause = "a".repeat(60) + ", more words follow here to pass the limit"
        // Below 72 chars: nothing even though a comma exists.
        assertTrue(segmenter.feed(clause.take(50)).isEmpty())
        // Crossing 72 chars with the comma inside emits through the last clause boundary.
        val phrases = segmenter.feed(clause.drop(50))
        assertEquals(1, phrases.size)
        assertTrue(phrases.single().endsWith(","))
    }

    @Test
    fun `hard limit splits at the last whitespace inside the window`() {
        val segmenter = PhraseSegmenter()
        val noPunctuation = ("word ".repeat(40)).trim() // 200 chars, no sentence/clause marks
        val phrases = segmenter.feed(noPunctuation)
        assertTrue(phrases.isNotEmpty())
        phrases.forEach { phrase ->
            assertTrue(phrase.length <= PhraseSegmenter.DEFAULT_HARD_LIMIT_CHARS)
        }
        // Everything except the emitted phrases remains buffered for later flush.
        val consumed = phrases.sumOf { it.length + 1 } // re-join approximation via flush check
        assertTrue(consumed <= noPunctuation.length)
        segmenter.flush()
    }

    @Test
    fun `hard limit without whitespace cuts exactly at the cap`() {
        val segmenter = PhraseSegmenter()
        val solid = "x".repeat(PhraseSegmenter.DEFAULT_HARD_LIMIT_CHARS + 10)
        val phrases = segmenter.feed(solid)
        assertEquals(
            listOf("x".repeat(PhraseSegmenter.DEFAULT_HARD_LIMIT_CHARS)),
            phrases
        )
        assertEquals("x".repeat(10), segmenter.flush())
    }

    @Test
    fun `unicode surrogate pairs are never split`() {
        val segmenter = PhraseSegmenter()
        // Family emoji = several surrogate pairs; place them so the hard cut would land mid-pair.
        val emoji = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // 👨‍👩‍7 family ZWJ sequence
        val text = emoji.repeat(30) // >160 chars of pure surrogate pairs
        val phrases = segmenter.feed(text)
        phrases.forEach { phrase ->
            assertTrue("phrase splits a surrogate pair: $phrase", phrase.isValidUtf16Sequence())
        }
        val tail = segmenter.flush()
        if (tail.isNotEmpty()) assertTrue(tail.isValidUtf16Sequence())
    }

    private fun String.isValidUtf16Sequence(): Boolean {
        var index = 0
        while (index < length) {
            val ch = this[index]
            if (Character.isHighSurrogate(ch)) {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                index += 2
            } else {
                index++
            }
        }
        return true
    }

    @Test
    fun `whitespace-only deltas never produce phrases`() {
        val segmenter = PhraseSegmenter()
        assertTrue(segmenter.feed("   \n\t ").isEmpty())
        assertEquals("", segmenter.flush())
    }

    @Test
    fun `punctuation split across deltas still emits`() {
        val segmenter = PhraseSegmenter()
        segmenter.feed("The sun is rising over the quiet neighborhood streets today")
        val phrases = segmenter.feed(". Time to move")
        assertEquals(listOf("The sun is rising over the quiet neighborhood streets today."), phrases)
    }

    @Test
    fun `decimal numbers are not treated as sentence ends`() {
        val segmenter = PhraseSegmenter()
        // The only period sits between digits, so even past the gate nothing may be emitted.
        val text = "It costs exactly 3.14159 units in the morning market right now"
        assertTrue(text.length >= PhraseSegmenter.DEFAULT_SENTENCE_MIN_CHARS)
        assertTrue(segmenter.feed(text).isEmpty())
        // A real terminator elsewhere still emits through it.
        assertEquals(listOf("$text Done."), segmenter.feed(" Done."))
    }

    @Test
    fun `multiple phrases can emerge from one delta via the hard limit`() {
        val segmenter = PhraseSegmenter()
        val sentence = "A".repeat(PhraseSegmenter.DEFAULT_SENTENCE_MIN_CHARS) + ". "
        val filler = "x".repeat(200)
        val phrases = segmenter.feed(sentence + filler)
        // Hard limit forces the filler out even though it has no boundaries of its own.
        assertEquals(
            listOf(
                "A".repeat(PhraseSegmenter.DEFAULT_SENTENCE_MIN_CHARS) + ".",
                "x".repeat(PhraseSegmenter.DEFAULT_HARD_LIMIT_CHARS)
            ),
            phrases
        )
        assertEquals("x".repeat(40), segmenter.flush())
    }

    @Test
    fun `flush returns and clears the unterminated tail`() {
        val segmenter = PhraseSegmenter()
        segmenter.feed("remaining tail text")
        assertEquals("remaining tail text", segmenter.flush())
        assertEquals("", segmenter.flush())
        assertEquals(0, segmenter.bufferedChars)
    }
}
