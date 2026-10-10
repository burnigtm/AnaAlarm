package com.anaalarm.support

import com.anaalarm.voice.SpeechOutput
import java.util.concurrent.CopyOnWriteArrayList

/** Test-clock driven speech. Playback start and completion are independent boundaries. */
internal class ControlledSpeaker : SpeechOutput {
    data class Utterance(val text: String, val start: () -> Unit, val done: () -> Unit, val turnId: Long? = null)
    val utterances = CopyOnWriteArrayList<Utterance>()
    val trace = CopyOnWriteArrayList<String>()
    override var onFocusLost: (() -> Unit)? = null
    private fun record(event: String) { trace += "${android.os.SystemClock.elapsedRealtime()}:$event" }
    var ready = true
    var languageCode = ""
    override val isReady get() = ready
    override suspend fun awaitReady(timeoutMs: Long) = ready
    override fun setLanguage(language: String) { this.languageCode = language }
    override fun setPitch(pitch: Float) = Unit
    override fun setSpeechRate(rate: Float) = Unit
    override fun speak(text: String, onStart: () -> Unit, completion: () -> Unit) {
        record("queued:$text")
        utterances += Utterance(text, onStart, completion)
    }
    override fun speakQueued(text: String, turnId: Long, sequence: Int, flush: Boolean, onStart: () -> Unit, completion: () -> Unit) {
        record("queued:$turnId:$sequence:$text")
        utterances += Utterance(text, onStart, completion, turnId)
    }
    override fun cancelQueuedTurn(turnId: Long) { utterances.removeAll { it.turnId == turnId }; record("cancel:$turnId") }
    override fun stop() { utterances.clear(); record("stop") }
    override fun shutdown() = stop()
    fun playNext() {
        val next = utterances.firstOrNull() ?: error("No queued speech")
        utterances.remove(next)
        record("start:${next.text}"); next.start()
        record("done:${next.text}"); next.done()
    }
}
