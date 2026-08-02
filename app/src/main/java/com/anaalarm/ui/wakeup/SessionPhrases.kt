package com.anaalarm.ui.wakeup

object SessionPhrases {

    val stopPhrases: List<String> = listOf(
        "stop", "i'm up", "im up", "i am up", "time to get up", "get up",
        "done", "bye", "goodbye", "para", "chega", "acordei", "levantei",
        "estou de pé", "to de pé", "de pé", "pode parar"
    )

    fun isStopPhrase(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return false
        return stopPhrases.any { lower.contains(it) }
    }
}
