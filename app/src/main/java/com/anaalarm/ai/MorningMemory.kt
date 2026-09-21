package com.anaalarm.ai

import com.anaalarm.data.ChatMessage

/**
 * Pulls 1–3 concrete “for today” plans out of a finished wake conversation and formats
 * the saved transcript Stats opens. Storage of those items lives in [com.anaalarm.data.MorningLog].
 */
object MorningMemory {

    const val GREETING_PROMPT = "Please begin the wake-up greeting now."
    const val MAX_ITEMS = 3
    const val MAX_ITEM_CHARS = 80
    const val MAX_TRANSCRIPT_CHARS = 4_000

    private val ENGLISH = Regex(
        """(?i)^(?:today i'm going to|today i am going to|today i will|today i'll|today i need to|today i have to|today i want to|today i plan to|i'm going to|i am going to|i will|i'll|i need to|i have to|i want to|i plan to)\s+(.+)$"""
    )
    private val PORTUGUESE = Regex(
        """(?i)^(?:hoje eu vou|hoje vou|eu vou|vou|eu preciso|preciso|eu tenho que|tenho que|eu quero|quero)\s+(.+)$"""
    )

    /** Up to [MAX_ITEMS] distinct plans spoken by the user, in conversation order. */
    fun extract(messages: List<ChatMessage>): List<String> {
        val found = mutableListOf<String>()
        for (message in messages) {
            if (message.role != "user") continue
            val content = message.content.trim()
            if (content.isEmpty() || content == GREETING_PROMPT) continue
            for (sentence in sentences(content)) {
                val item = planItem(sentence) ?: continue
                if (found.none { it.equals(item, ignoreCase = true) }) {
                    found += item
                }
                if (found.size == MAX_ITEMS) return found.toList()
            }
        }
        return found
    }

    /** Role-labelled transcript with the synthetic greeting prompt removed. */
    fun formatTranscript(messages: List<ChatMessage>): String {
        return messages.mapNotNull { message ->
            val content = message.content.trim()
            if (content.isEmpty()) return@mapNotNull null
            if (message.role == "user" && content == GREETING_PROMPT) return@mapNotNull null
            val speaker = if (message.role == "assistant") "Ana" else "You"
            "$speaker: $content"
        }.joinToString("\n").take(MAX_TRANSCRIPT_CHARS)
    }

    private fun sentences(text: String): List<String> =
        text.split(Regex("""(?<=[.!?])\s+|[\n;]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun planItem(sentence: String): String? {
        val match = ENGLISH.find(sentence) ?: PORTUGUESE.find(sentence) ?: return null
        val raw = match.groupValues[1]
            .trim()
            .trimEnd('.', '!', '?', ',', ';', ':')
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (raw.length < 3 || raw.length > MAX_ITEM_CHARS) return null
        return raw
    }
}
