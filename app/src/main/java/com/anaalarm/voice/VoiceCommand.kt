package com.anaalarm.voice

import com.anaalarm.ui.wakeup.SessionPhrases
import java.text.Normalizer
import java.util.Locale

internal enum class VoiceCommand { STOP, SNOOZE, REPEAT, TYPE, COMPLETE, SKIP, CANCEL }

/** Deliberately small vocabulary: only complete utterances activate new controls. */
internal object VoiceCommands {
    fun normalize(text: String): String = Normalizer.normalize(text.replace('−', '-').lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('’', '\'')
        .replace(Regex("[^\\p{L}\\p{N}'-]+"), " ").trim()

    fun parse(text: String, routine: Boolean = false, challenge: Boolean = false): VoiceCommand? {
        val value = normalize(text)
        if (Regex("\\b(don't|dont|do not|not|never|nao|nunca)\\b").containsMatchIn(value)) return null
        return when {
            value in setOf("snooze", "snooze alarm", "adiar", "soneca") -> VoiceCommand.SNOOZE
            value in setOf("repeat", "repeat that", "say that again", "repita", "repetir", "repita isso") -> VoiceCommand.REPEAT
            value in setOf("type", "switch to typing", "let me type", "digitar", "quero digitar") -> VoiceCommand.TYPE
            challenge && value in setOf("cancel", "cancel challenge", "cancelar") -> VoiceCommand.CANCEL
            routine && value in setOf("done", "completed", "mark done", "feito", "concluido", "terminei") -> VoiceCommand.COMPLETE
            routine && value in setOf("skip", "next", "pular", "proximo") -> VoiceCommand.SKIP
            SessionPhrases.isStopPhrase(text.replace('’', '\'')) -> VoiceCommand.STOP
            else -> null
        }
    }
}

/** Strict number parsing. Codes are digit sequences; arithmetic answers can be cardinal numbers. */
internal object SpokenNumbers {
    private val digits = mapOf("zero" to 0, "oh" to 0, "one" to 1, "um" to 1, "uma" to 1,
        "two" to 2, "dois" to 2, "duas" to 2, "three" to 3, "tres" to 3, "four" to 4,
        "quatro" to 4, "five" to 5, "cinco" to 5, "six" to 6, "seis" to 6, "seven" to 7,
        "sete" to 7, "eight" to 8, "oito" to 8, "nine" to 9, "nove" to 9)
    private val small = mapOf("ten" to 10, "dez" to 10, "eleven" to 11, "onze" to 11,
        "twelve" to 12, "doze" to 12, "thirteen" to 13, "treze" to 13, "fourteen" to 14,
        "quatorze" to 14, "catorze" to 14, "fifteen" to 15, "quinze" to 15,
        "sixteen" to 16, "dezesseis" to 16, "seventeen" to 17, "dezessete" to 17,
        "eighteen" to 18, "dezoito" to 18, "nineteen" to 19, "dezenove" to 19)
    private val tens = mapOf("twenty" to 20, "vinte" to 20, "thirty" to 30, "trinta" to 30,
        "forty" to 40, "quarenta" to 40, "fifty" to 50, "cinquenta" to 50,
        "sixty" to 60, "sessenta" to 60, "seventy" to 70, "setenta" to 70,
        "eighty" to 80, "oitenta" to 80, "ninety" to 90, "noventa" to 90)

    fun parse(text: String, code: Boolean): String? {
        val normalized = VoiceCommands.normalize(text)
        if (code && '-' in normalized) return null
        if (normalized.matches(Regex(if (code) "[0-9]{4}" else "-?[0-9]{1,4}"))) return normalized
        if (normalized.startsWith('-')) return null
        val words = normalized.replace('-', ' ').split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        if (code) {
            if (words.size != 4) return null
            return words.map { digits[it] ?: it.toIntOrNull()?.takeIf { n -> n in 0..9 } ?: return null }.joinToString("")
        }
        val negative = words.first() in setOf("minus", "negative", "menos")
        val tokens = if (negative) words.drop(1) else words
        val number = when {
            tokens.size == 1 -> digits[tokens[0]] ?: small[tokens[0]] ?: tens[tokens[0]] ?: if (tokens[0] == "cem") 100 else null
            tokens == listOf("one", "hundred") -> 100
            tokens.firstOrNull() == "cento" && tokens.size == 3 && tokens[1] == "e" -> digits[tokens[2]]?.takeIf { it > 0 }?.plus(100)
            tokens.take(2) == listOf("one", "hundred") -> {
                val tail = tokens.drop(2).let { if (it.firstOrNull() == "and") it.drop(1) else it }
                if (tail.size == 1) digits[tail[0]]?.takeIf { it > 0 }?.plus(100) else null
            }
            tokens.size == 2 -> tens[tokens[0]]?.let { t -> digits[tokens[1]]?.takeIf { it > 0 }?.plus(t) }
            tokens.size == 3 && tokens[1] == "e" -> tens[tokens[0]]?.let { t -> digits[tokens[2]]?.takeIf { it > 0 }?.plus(t) }
            else -> null
        } ?: return null
        return (if (negative) -number else number).toString()
    }
}
