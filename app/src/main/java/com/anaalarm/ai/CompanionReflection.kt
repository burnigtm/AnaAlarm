package com.anaalarm.ai

import com.anaalarm.data.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only source-grounded, explicit user statements can become automatic memories. */
object CompanionReflection {
    @Serializable
    data class Candidate(val content: String, val kind: String, val quote: String, val confident: Boolean = false)
    @Serializable
    data class Reflection(val recap: String = "", val memories: List<Candidate> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, userStatements: List<String>): Reflection? = runCatching {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val result = json.decodeFromString(Reflection.serializer(), clean)
        result.copy(
            recap = result.recap.trim().take(900),
            memories = result.memories.filter { candidate ->
                candidate.confident && candidate.kind in setOf("fact", "goal") &&
                    candidate.content.isNotBlank() && candidate.content.length <= 240 &&
                    candidate.quote.trim().length >= 8 &&
                    userStatements.any { it.contains(candidate.quote.trim(), ignoreCase = true) }
            }.take(5)
        )
    }.getOrNull()

    fun fallbackRecap(history: List<ChatMessage>): String = history
        .filter { it.role == "user" && it.content != GREETING }
        .takeLast(5)
        .joinToString("\n") { it.content.trim().take(170) }
        .take(900)
        .ifBlank { history.lastOrNull { it.role == "assistant" }?.content?.take(900).orEmpty() }

    const val GREETING = "Please begin the wake-up greeting now."
}
