package com.anaalarm.debug

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import java.util.concurrent.CopyOnWriteArrayList

/** Only included in debug APKs. Tests select this component explicitly; no system setting changes. */
class ScriptedRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) {
        ScriptedRecognition.request = Intent(intent)
        ScriptedRecognition.callback = listener
        ScriptedRecognition.requests++
        ScriptedRecognition.record("start:${intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE)}")
        listener.readyForSpeech(Bundle())
    }

    override fun onStopListening(listener: Callback) { ScriptedRecognition.record("stop") }
    override fun onCancel(listener: Callback) {
        if (ScriptedRecognition.callback === listener) ScriptedRecognition.callback = null
        ScriptedRecognition.record("cancel")
    }
}

object ScriptedRecognition {
    @Volatile var callback: RecognitionService.Callback? = null
    @Volatile var request: Intent? = null
    @Volatile var requests = 0
    val trace = CopyOnWriteArrayList<String>()
    fun record(event: String) { trace += "${android.os.SystemClock.elapsedRealtime()}:$event" }
    fun reset() { callback = null; request = null; requests = 0; trace.clear() }
    fun partial(text: String) { record("partial:$text"); callback?.partialResults(result(text)) }
    fun speech(level: Float) { record("speech:$level"); callback?.beginningOfSpeech(); callback?.rmsChanged(level) }
    fun final(text: String) {
        val current = callback ?: return
        record("final:$text")
        callback = null
        current.endOfSpeech()
        current.results(result(text))
    }
    fun error(code: Int) {
        val current = callback ?: return
        callback = null
        record("error:$code")
        current.error(code)
    }
    private fun result(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }
}
