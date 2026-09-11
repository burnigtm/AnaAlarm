package com.anaalarm.ui.wakeup

import android.speech.SpeechRecognizer
import android.util.Log
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.voice.SpeechListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Microphone listen cycle for a wake session: start, soft-retry, hard fallback to text.
 * Owns generation-scoped timeout/retry jobs; [SessionController] remains the façade.
 */
internal class SessionListenCycle(
    private val app: AnaAlarmApp,
    private val scope: CoroutineScope,
    private val host: Host
) {
    interface Host {
        fun isTerminal(): Boolean
        fun isTextInputActive(): Boolean
        var status: SessionStatus
        var partialUserText: String
        var voiceHint: String?
        var silentStreak: Int
        fun voiceGeneration(): Long
        fun nextVoiceGeneration(): Long
        fun isCurrentVoiceGeneration(generation: Long): Boolean
        fun wrapUp()
        fun switchToTextInput(speakPrompt: Boolean)
        fun speak(text: String)
        fun languageTag(): String
        fun isTimeUp(): Boolean
    }

    private var settleJob: Job? = null
    private var listenTimeoutJob: Job? = null
    private var listenRetryJob: Job? = null

    fun scheduleAfterSpeaking() {
        if (host.isTerminal()) return
        if (host.isTimeUp()) {
            host.wrapUp()
            return
        }
        val generation = host.nextVoiceGeneration()
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(MIC_SETTLE_MS)
            if (!host.isCurrentVoiceGeneration(generation)) return@launch
            host.status = SessionStatus.LISTENING
            if (host.isTextInputActive()) {
                host.voiceHint = app.getString(R.string.type_fallback_hint)
            } else {
                startListeningAttempt()
            }
        }
    }

    fun startListeningAttempt() {
        if (host.isTerminal() || host.isTextInputActive()) return
        if (!app.speechListener.isAvailable) {
            host.switchToTextInput(speakPrompt = true)
            return
        }
        settleJob?.cancel()
        listenTimeoutJob?.cancel()
        listenRetryJob?.cancel()
        val generation = host.nextVoiceGeneration()
        host.status = SessionStatus.LISTENING
        host.partialUserText = ""
        host.voiceHint = app.getString(R.string.status_listening)
        app.speechListener.startListening(host.languageTag())
        listenTimeoutJob = scope.launch {
            delay(LISTEN_TIMEOUT_MS)
            if (!host.isCurrentVoiceGeneration(generation) || host.isTextInputActive()) return@launch
            app.speechListener.stopListening()
            host.silentStreak++
            when {
                host.isTimeUp() -> host.wrapUp()
                host.silentStreak >= MAX_SILENT_CYCLES -> host.switchToTextInput(speakPrompt = true)
                else -> host.speak(app.getString(R.string.did_not_catch))
            }
        }
    }

    fun onPartialSpeech(text: String) {
        if (host.isTerminal() || host.status != SessionStatus.LISTENING) return
        host.partialUserText = text
    }

    fun onSpeechError(code: Int) {
        if (host.isTerminal() || host.status != SessionStatus.LISTENING) return
        listenTimeoutJob?.cancel()
        listenTimeoutJob = null
        host.partialUserText = ""

        // 12/13 are language-not-supported/language-unavailable on API 31+. SpeechListener first
        // retries an on-device engine with the default recognizer before exposing either error.
        val hardFailure = code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            code == SpeechRecognizer.ERROR_CLIENT ||
            code == SpeechRecognizer.ERROR_AUDIO ||
            code == SpeechRecognizer.ERROR_NETWORK ||
            code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            code == SpeechRecognizer.ERROR_SERVER ||
            code == 12 || code == 13

        Log.w(TAG, "Speech error ${SpeechListener.errorName(code)} hard=$hardFailure")
        host.voiceHint = app.getString(R.string.mic_error)
        if (hardFailure) {
            host.switchToTextInput(speakPrompt = true)
            return
        }

        host.silentStreak++
        if (host.silentStreak >= MAX_SILENT_CYCLES) {
            host.switchToTextInput(speakPrompt = true)
            return
        }
        val generation = host.voiceGeneration()
        listenRetryJob?.cancel()
        listenRetryJob = scope.launch {
            delay(LISTEN_RETRY_MS)
            if (host.isCurrentVoiceGeneration(generation) && !host.isTextInputActive()) {
                startListeningAttempt()
            }
        }
    }

    fun cancelListenCycle(stopRecognizer: Boolean) {
        host.nextVoiceGeneration()
        settleJob?.cancel()
        settleJob = null
        listenTimeoutJob?.cancel()
        listenTimeoutJob = null
        listenRetryJob?.cancel()
        listenRetryJob = null
        if (stopRecognizer) app.speechListener.stopListening()
    }

    companion object {
        private const val TAG = "AnaSession"
        private const val LISTEN_TIMEOUT_MS = 25_000L
        private const val MIC_SETTLE_MS = 175L
        private const val LISTEN_RETRY_MS = 350L
        const val MAX_SILENT_CYCLES = 2
    }
}
