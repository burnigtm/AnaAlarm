package com.anaalarm.alarm

import kotlin.random.Random

/**
 * Locally generated stop-challenges: proving you are awake before the alarm stops, with zero
 * API cost and no network dependency. Pure logic so the contract is covered by fast JVM tests.
 */
object DismissalChallenges {

    /** Per-alarm challenge kind; codes are persisted in Room. */
    enum class Type(val code: Int) {
        NONE(0),
        MATH(1),
        MEMORY(2);

        companion object {
            fun from(code: Int): Type = entries.firstOrNull { it.code == code } ?: NONE
        }
    }

    data class MathQuestion(val prompt: String, val answer: Int)

    private const val MEMORY_CODE_LENGTH = 4

    /** Mental-math question: addition, subtraction, or a small product. */
    fun mathQuestion(random: Random = Random.Default): MathQuestion {
        val kind = random.nextInt(3)
        return when (kind) {
            0 -> {
                val a = random.nextInt(12, 60)
                val b = random.nextInt(11, 40)
                MathQuestion("$a + $b", a + b)
            }
            1 -> {
                val a = random.nextInt(30, 100)
                val b = random.nextInt(11, 30)
                MathQuestion("$a − $b", a - b)
            }
            else -> {
                val a = random.nextInt(3, 13)
                val b = random.nextInt(3, 10)
                MathQuestion("$a × $b", a * b)
            }
        }
    }

    /** Digit sequence to memorize; shown briefly, then typed back from memory. */
    fun memoryCode(random: Random = Random.Default): String =
        (1..MEMORY_CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }

    /** Whitespace-tolerant comparison so sloppy half-asleep typing is not punished twice. */
    fun isAnswerCorrect(expected: String, typed: String): Boolean =
        typed.filterNot { it.isWhitespace() } == expected
}
