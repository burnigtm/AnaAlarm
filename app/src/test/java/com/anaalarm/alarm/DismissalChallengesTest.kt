package com.anaalarm.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DismissalChallengesTest {

    @Test
    fun `math questions stay within mental-math ranges and carry correct answers`() {
        val random = Random(42)
        repeat(200) {
            val question = DismissalChallenges.mathQuestion(random)
            val operands = question.prompt.split(" ")
            assertTrue(question.prompt.contains('+') || question.prompt.contains('−') ||
                question.prompt.contains('×'))
            // Every generated question must be self-consistent.
            when {
                "+" in question.prompt -> {
                    assertEquals(question.answer, operands[0].toInt() + operands[2].toInt())
                    assertTrue(question.answer <= 59 + 39)
                }
                "−" in question.prompt -> {
                    assertEquals(question.answer, operands[0].toInt() - operands[2].toInt())
                    assertTrue(question.answer >= 0)
                }
                else -> {
                    assertEquals(question.answer, operands[0].toInt() * operands[2].toInt())
                    assertTrue(question.answer <= 12 * 9)
                }
            }
        }
    }

    @Test
    fun `memory codes are four digits`() {
        val random = Random(7)
        repeat(50) {
            val code = DismissalChallenges.memoryCode(random)
            assertEquals(4, code.length)
            assertTrue(code.all { it.isDigit() })
        }
    }

    @Test
    fun `answer comparison tolerates whitespace but not wrong values`() {
        assertTrue(DismissalChallenges.isAnswerCorrect("42", "42"))
        assertTrue(DismissalChallenges.isAnswerCorrect("42", " 42 "))
        assertTrue(DismissalChallenges.isAnswerCorrect("1234", "1 2 3 4"))
        assertFalse(DismissalChallenges.isAnswerCorrect("42", "43"))
        assertFalse(DismissalChallenges.isAnswerCorrect("1234", "1243"))
    }

    @Test
    fun `type codes round trip and unknown codes fall back to none`() {
        assertEquals(DismissalChallenges.Type.NONE, DismissalChallenges.Type.from(0))
        assertEquals(DismissalChallenges.Type.MATH, DismissalChallenges.Type.from(1))
        assertEquals(DismissalChallenges.Type.MEMORY, DismissalChallenges.Type.from(2))
        assertEquals(DismissalChallenges.Type.NONE, DismissalChallenges.Type.from(99))
    }
}
