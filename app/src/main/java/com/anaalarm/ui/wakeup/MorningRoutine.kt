package com.anaalarm.ui.wakeup

/** Pure progression; persistence must succeed before the session advances this state. */
internal class MorningRoutine(habits: List<String>, completed: Set<String>) {
    private val remaining = habits.map { it.trim() }.filter { it.isNotEmpty() && it !in completed }.distinct()
    var index = 0
        private set
    var finished = false
        private set
    val habit: String? get() = remaining.getOrNull(index)
    val total: Int get() = remaining.size
    val awaitingPriority: Boolean get() = habit == null && !finished
    fun advance() { if (habit != null) index++ }
    fun finish() { if (awaitingPriority) finished = true }
}
