package com.anaalarm.data

/** Raw daily-log text plus the short “for today” lines packed beside it. */
data class MorningRecap(val summary: String, val items: List<String>)

/**
 * Packs up to three “for today” lines onto a daily-log summary so the home recap can
 * show them apart from the raw transcript text. The marker is stripped before the
 * summary is shown or appended.
 */
object MorningLog {
    const val SEPARATOR = "\n<<<ana-for-today>>>\n"
    const val MAX_ITEMS = 3

    fun pack(summary: String, items: List<String>): String {
        val body = summaryBody(summary).trim()
        val clean = mergeItems(emptyList(), items)
        if (clean.isEmpty()) return body
        return body + SEPARATOR + clean.joinToString("\n")
    }

    fun unpack(stored: String): MorningRecap {
        val index = stored.indexOf(SEPARATOR)
        if (index < 0) return MorningRecap(stored.trim(), emptyList())
        val body = stored.substring(0, index).trim()
        val items = stored.substring(index + SEPARATOR.length)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_ITEMS)
            .toList()
        return MorningRecap(body, items)
    }

    fun summaryBody(stored: String): String = unpack(stored).summary

    /** Keeps the newest distinct items when a later session adds more than [MAX_ITEMS]. */
    fun mergeItems(existing: List<String>, incoming: List<String>): List<String> {
        val merged = LinkedHashMap<String, String>()
        (existing + incoming).forEach { raw ->
            val clean = raw.trim()
            if (clean.isNotEmpty()) merged[clean.lowercase()] = clean
        }
        return merged.values.toList().takeLast(MAX_ITEMS)
    }
}
