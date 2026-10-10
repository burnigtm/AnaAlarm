package com.anaalarm.voice

import org.junit.Assert.*
import org.junit.Test

class SpokenNumbersTest {
    @Test fun `leading minus before words is ambiguous and cannot become a positive answer`() {
        assertNull(SpokenNumbers.parse("-one", false))
        assertNull(SpokenNumbers.parse("−um", false))
        assertNull(SpokenNumbers.parse("--1", false))
    }
    @Test fun `code words and numeric digits preserve leading zeros`() {
        listOf("zero zero one two", "zero zero um dois", "0 zero 1 dois", "0012").forEach {
            assertEquals(it, "0012", SpokenNumbers.parse(it, code = true))
        }
    }
    @Test fun `codes must contain exactly four unambiguous digits`() {
        listOf("twelve thirty four", "zero one", "one two three four five", "um ou dois", "-0012",
            "one two and three four", "", "123", "12345").forEach { assertNull(it, SpokenNumbers.parse(it, true)) }
    }
    @Test fun `arithmetic accepts signed digits and bilingual cardinal numbers`() {
        mapOf("forty-two" to "42", "quarenta e dois" to "42", "vinte três" to "23",
            "minus seven" to "-7", "−12" to "-12", "menos cinco" to "-5", "nineteen" to "19",
            "dezesseis" to "16", "onze" to "11", "zero" to "0", "-12" to "-12", "one hundred eight" to "108", "cento e oito" to "108").forEach { (text, expected) ->
            assertEquals(text, expected, SpokenNumbers.parse(text, false))
        }
    }
    @Test fun `ambiguous and malformed math answers are rejected`() {
        listOf("two three", "42 or 43", "two plus two", "twenty zero", "twenty twenty", "minus",
            "two hundred", "12.5", "one and two", "nothing").forEach { assertNull(it, SpokenNumbers.parse(it, false)) }
    }
    @Test fun `hundreds and malformed composites stay unambiguous`() {
        mapOf("cem" to "100", "one hundred" to "100", "one hundred and eight" to "108", "cento e um" to "101").forEach { (text, expected) ->
            assertEquals(expected, SpokenNumbers.parse(text, false))
        }
        listOf("cento", "cento oito", "cento e zero", "cento e dez", "cento e banana", "cento e one extra",
            "one hundred and", "one hundred and zero", "one hundred banana", "one hundred one two",
            "forty e banana", "banana e one", "forty e zero").forEach { assertNull(it, SpokenNumbers.parse(it, false)) }
        listOf("1 2 3 10", "1 2 3 banana", "zero one two -9").forEach { assertNull(it, SpokenNumbers.parse(it, true)) }
    }
    @Test fun `all digits teens and tens map correctly`() {
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine").forEachIndexed { i, s ->
            assertEquals(i.toString(), SpokenNumbers.parse(s, false))
        }
        listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen").forEachIndexed { i, s ->
            assertEquals((i + 10).toString(), SpokenNumbers.parse(s, false))
        }
        listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety").forEachIndexed { i, s ->
            assertEquals(((i + 2) * 10).toString(), SpokenNumbers.parse(s, false))
        }
    }
}
