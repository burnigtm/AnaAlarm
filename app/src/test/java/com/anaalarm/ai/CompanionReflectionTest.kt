package com.anaalarm.ai

import com.anaalarm.data.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class CompanionReflectionTest {
    @Test fun `only explicit source grounded confident memories are accepted`() {
        val result = CompanionReflection.parse("""{"recap":"A good morning","memories":[
            {"content":"Call mom today","kind":"goal","quote":"I will call mom today","confident":true},
            {"content":"Owns a dog","kind":"fact","quote":"I own a dog","confident":true},
            {"content":"Likes coffee","kind":"fact","quote":"I will call mom today","confident":false}
        ]}""", listOf("I will call mom today"))!!
        assertEquals(listOf("Call mom today"), result.memories.map { it.content })
    }

    @Test fun `malformed output does not create memories`() {
        assertNull(CompanionReflection.parse("Here are your memories", listOf("hello")))
    }

    @Test fun `offline recap keeps recent user goals and excludes synthetic greeting`() {
        val history = listOf(ChatMessage("user", CompanionReflection.GREETING),
            ChatMessage("assistant", "a".repeat(1200)), ChatMessage("user", "I will call mom today"))
        assertEquals("I will call mom today", CompanionReflection.fallbackRecap(history))
    }
}
