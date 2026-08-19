package com.anaalarm.ui.wakeup

object SessionPhrases {

    val stopPhrases: List<String> = listOf(
        "stop", "i'm up", "im up", "i am up", "time to get up", "get up",
        "done", "bye", "goodbye", "para", "chega", "acordei", "levantei",
        "estou de pé", "to de pé", "de pé", "pode parar"
    )

    /**
     * Short tokens that appear in ordinary speech. Match only as the entire utterance
     * (after punctuation/case normalisation), never as a word inside a longer sentence.
     */
    private val standalonePhrases = setOf(
        "stop", "done", "bye", "para", "chega", "get up"
    )

    private val containedPhrases = stopPhrases
        .filterNot { it in standalonePhrases }
        .sortedByDescending { it.length }

    fun isStopPhrase(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        if (normalized in standalonePhrases) return true
        return containedPhrases.any { containsWholePhrase(normalized, it) }
    }

    private fun normalize(text: String): String {
        val lowered = text.lowercase().trim()
        val builder = StringBuilder(lowered.length)
        for (ch in lowered) {
            when {
                ch.isLetterOrDigit() || ch == '\'' -> builder.append(ch)
                ch.isWhitespace() || ch in PUNCTUATION -> {
                    if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
                }
            }
        }
        return builder.toString().trim()
    }

    private fun containsWholePhrase(haystack: String, phrase: String): Boolean {
        var start = 0
        while (start <= haystack.length - phrase.length) {
            val index = haystack.indexOf(phrase, start)
            if (index < 0) return false
            val end = index + phrase.length
            val beforeOk = index == 0 || haystack[index - 1] == ' '
            val afterOk = end == haystack.length || haystack[end] == ' '
            if (beforeOk && afterOk) return true
            start = index + 1
        }
        return false
    }

    private const val PUNCTUATION = ".,!?;:\"“”‘’-—…"
}
