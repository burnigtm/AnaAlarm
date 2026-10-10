package com.anaalarm.debug

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import java.util.concurrent.CopyOnWriteArrayList

/** Framework integration engine. Produces a short tone rather than depending on installed voices. */
class ScriptedTtsService : TextToSpeechService() {
    override fun onIsLanguageAvailable(language: String, country: String, variant: String): Int =
        if (language in setOf("eng", "por", "en", "pt")) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    override fun onLoadLanguage(language: String, country: String, variant: String): Int =
        onIsLanguageAvailable(language, country, variant)
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
    override fun onStop() { ScriptedTts.trace += "${android.os.SystemClock.elapsedRealtime()}:stop" }
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        ScriptedTts.trace += "${android.os.SystemClock.elapsedRealtime()}:${request.language}:${request.charSequenceText}"
        if (ScriptedTts.fail) { callback.error(); return }
        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
        // 80 ms PCM tone, bounded by the engine callback's preferred buffer size.
        val audio = ByteArray(2560)
        for (i in 0 until audio.size / 2) {
            val sample = (kotlin.math.sin(i * 2.0 * Math.PI * 440 / 16000) * 2000).toInt()
            audio[i * 2] = sample.toByte(); audio[i * 2 + 1] = (sample shr 8).toByte()
        }
        var offset = 0
        while (offset < audio.size) {
            val length = minOf(callback.maxBufferSize, audio.size - offset)
            if (callback.audioAvailable(audio, offset, length) != TextToSpeech.SUCCESS) return
            offset += length
        }
        callback.done()
    }
}

object ScriptedTts {
    @Volatile var fail = false
    val trace = CopyOnWriteArrayList<String>()
    fun reset() { fail = false; trace.clear() }
}
