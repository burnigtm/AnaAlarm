package com.anaalarm.voice

/**
 * Ordered registry for multi-phrase streaming turns.
 *
 * The single-active [UtteranceRegistry] cannot safely represent QUEUE_ADD speech: a streamed
 * turn owns several utterances whose ids carry both a turn generation and a monotonically
 * increasing phrase sequence. This registry stores all of them so late callbacks can be matched
 * exactly, watchdogs can be cancelled individually, and a whole turn can be cleared at once.
 */
internal class OrderedUtteranceRegistry {

    data class Entry(
        val id: String,
        val turnId: Long,
        val sequence: Int,
        val onStart: (Long) -> Unit,
        val onComplete: () -> Unit,
        val watchdog: Runnable,
        val onStartMissing: (String) -> Unit = {},
        var started: Boolean = false
    )

    private val active = LinkedHashMap<String, Entry>()

    @Synchronized
    fun register(entry: Entry) {
        active[entry.id] = entry
    }

    /** Marks an utterance audible and returns its start callback; null when stale/unknown. */
    @Synchronized
    fun markStarted(id: String): ((Long) -> Unit)? {
        val entry = active[id]?.takeIf { !it.started } ?: return null
        entry.started = true
        return entry.onStart
    }

    /** Completes one utterance and returns it; null when the id is unknown. */
    @Synchronized
    fun finish(id: String): Entry? {
        val entry = active.remove(id) ?: return null
        return entry
    }

    /** Removes every outstanding utterance of one turn, returning them in enqueue order. */
    @Synchronized
    fun clearTurn(turnId: Long): List<Entry> {
        val removed = active.values.filter { it.turnId == turnId }
        removed.forEach { active.remove(it.id) }
        return removed
    }

    /** Removes every outstanding utterance regardless of turn, returning them in enqueue order. */
    @Synchronized
    fun clearAll(): List<Entry> {
        val removed = active.values.toList()
        active.clear()
        return removed
    }

    @Synchronized
    fun get(id: String): Entry? = active[id]

    /** Number of utterances still awaiting completion across all turns. */
    @Synchronized
    fun outstandingCount(): Int = active.size

    /** Number of not-yet-audible utterances of one turn still held by the engine queue. */
    @Synchronized
    fun unstartedCount(turnId: Long): Int = active.values.count { it.turnId == turnId && !it.started }
}
