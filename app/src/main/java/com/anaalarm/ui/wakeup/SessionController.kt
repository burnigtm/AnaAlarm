package com.anaalarm.ui.wakeup

import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.ai.ApiException
import com.anaalarm.voice.SpeechListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class SessionStatus { STARTING, SPEAKING, LISTENING, THINKING, ENDED }

class SessionController(
    private val app: AnaAlarmApp,
    private val scope: CoroutineScope,
    private val onFinished: () -> Unit,
    voiceAvailable: Boolean
) {

    var status by mutableStateOf(SessionStatus.STARTING)
        private set
    var aiText by mutableStateOf("")
        private set
    var lastUserText by mutableStateOf("")
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var voiceHint by mutableStateOf<String?>(null)
        private set

    /** When true the UI shows a text field for answers (mic missing or unproductive). */
    var textInputActive by mutableStateOf(!voiceAvailable)
        private set

    private var languageTag = "en-US"
    private var sessionMs = 10 * 60_000L
    private val sessionStart = SystemClock.elapsedRealtime()
    private var ended = false
    private var silentStreak = 0
    private var turnInFlight = false
    private var suppressNextErrors = false

    companion object {
        private const val TAG = "AnaSession"
        private const val LISTEN_TIMEOUT_MS = 25_000L
        private const val MAX_SILENT_CYCLES = 2
    }

    fun start() {
        app.speechListener.onResult = { text -> onUserSpeech(text) }
        app.speechListener.onError = { code -> onSpeechError(code) }
        scope.launch {
            val settings = app.settingsStore.settings.first()
            languageTag = if (settings.language == "pt") "pt-BR" else "en-US"
            sessionMs = settings.sessionMinutes * 60_000L
            var ready = app.ttsManager.awaitReady(6_000)
            if (!ready) {
                Log.w(TAG, "TTS not ready — recreating engine")
                app.recreateTts()
                ready = app.ttsManager.awaitReady(8_000)
            }
            Log.i(TAG, "TTS ready=$ready speechAvailable=${app.speechListener.isAvailable}")
            app.ttsManager.setLanguage(settings.language)
            app.ttsManager.setPitch(1.05f)
            app.ttsManager.setSpeechRate(1.0f)

            if (!app.speechListener.isAvailable) {
                app.recreateSpeech()
            }
            app.speechListener.onResult = { text -> onUserSpeech(text) }
            app.speechListener.onError = { code -> onSpeechError(code) }

            if (!textInputActive && !app.speechListener.isAvailable) {
                switchToTextInput(speakPrompt = false)
            }
            beginSession()
        }
    }

    fun enableTextInput() {
        switchToTextInput(speakPrompt = false)
    }

    private suspend fun beginSession() {
        if (ended) return
        status = SessionStatus.STARTING
        try {
            val greeting = app.conversationEngine.startSession()
            Log.i(TAG, "Greeting received len=${greeting.length}")
            speak(greeting)
        } catch (e: Exception) {
            Log.e(TAG, "beginSession failed", e)
            fail(e)
        }
    }

    private fun speak(text: String) {
        aiText = text
        status = SessionStatus.SPEAKING
        voiceHint = null
        suppressNextErrors = true
        app.speechListener.stopListening()
        app.ttsManager.speak(text) {
            afterSpeaking()
        }
    }

    private fun afterSpeaking() {
        if (ended) return
        if (isTimeUp()) {
            wrapUp()
            return
        }
        scope.launch {
            // Let the audio path settle before opening the mic.
            delay(700)
            if (ended) return@launch
            status = SessionStatus.LISTENING
            if (textInputActive) {
                voiceHint = app.getString(R.string.type_fallback_hint)
                return@launch
            }
            listen()
        }
    }

    private fun listen() {
        if (ended || textInputActive) return
        if (!app.speechListener.isAvailable) {
            switchToTextInput(speakPrompt = true)
            return
        }
        voiceHint = app.getString(R.string.status_listening)
        // Allow the next recognizer error through (stopListening often emits a benign one).
        suppressNextErrors = true
        app.speechListener.startListening(languageTag)
        // Clear suppress after a beat so real mic failures count.
        scope.launch {
            delay(900)
            suppressNextErrors = false
        }
        scope.launch {
            delay(LISTEN_TIMEOUT_MS)
            if (ended || status != SessionStatus.LISTENING || textInputActive) return@launch
            silentStreak++
            when {
                isTimeUp() -> wrapUp()
                silentStreak >= MAX_SILENT_CYCLES -> switchToTextInput(speakPrompt = true)
                else -> speak(app.getString(R.string.did_not_catch))
            }
        }
    }

    private fun onUserSpeech(text: String) {
        if (ended) return
        lastUserText = text
        silentStreak = 0
        voiceHint = null
        Log.i(TAG, "User said: $text")
        if (SessionPhrases.isStopPhrase(text)) {
            wrapUp()
            return
        }
        if (turnInFlight) return
        turnInFlight = true
        status = SessionStatus.THINKING
        scope.launch {
            try {
                val reply = app.conversationEngine.respond(text)
                turnInFlight = false
                speak(reply)
            } catch (e: Exception) {
                turnInFlight = false
                fail(e)
            }
        }
    }

    private fun onSpeechError(code: Int) {
        if (ended) return
        if (status != SessionStatus.LISTENING && status != SessionStatus.SPEAKING) return

        // 13 = ERROR_LANGUAGE_NOT_SUPPORTED / LANGUAGE_UNAVAILABLE on newer APIs
        val hardFailure = code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            code == SpeechRecognizer.ERROR_CLIENT ||
            code == SpeechRecognizer.ERROR_AUDIO ||
            code == SpeechRecognizer.ERROR_NETWORK ||
            code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            code == SpeechRecognizer.ERROR_SERVER ||
            code == 13

        // Only suppress soft/benign errors right after TTS stops the mic.
        if (suppressNextErrors && !hardFailure) {
            Log.d(TAG, "Suppressed soft speech error $code")
            suppressNextErrors = false
            return
        }
        suppressNextErrors = false
        if (status != SessionStatus.LISTENING) return

        Log.w(TAG, "Speech error ${SpeechListener.errorName(code)} hard=$hardFailure streak=$silentStreak")
        voiceHint = app.getString(R.string.mic_error)

        if (hardFailure) {
            switchToTextInput(speakPrompt = true)
            return
        }

        silentStreak++
        if (silentStreak >= MAX_SILENT_CYCLES) {
            switchToTextInput(speakPrompt = true)
            return
        }
        scope.launch {
            delay(800)
            if (!ended && status == SessionStatus.LISTENING && !textInputActive) {
                listen()
            }
        }
    }

    private fun switchToTextInput(speakPrompt: Boolean) {
        if (ended) return
        if (!textInputActive) {
            textInputActive = true
            voiceHint = app.getString(R.string.type_fallback_switch)
        }
        suppressNextErrors = true
        app.speechListener.stopListening()
        if (speakPrompt) {
            scope.launch {
                app.ttsManager.speak(app.getString(R.string.type_fallback_switch)) { }
            }
        }
    }

    fun submitText(text: String) {
        if (text.isBlank() || ended) return
        onUserSpeech(text)
    }

    fun stopNow() {
        ended = true
        suppressNextErrors = true
        app.speechListener.stopListening()
        app.ttsManager.stop()
        status = SessionStatus.ENDED
        scope.launch { app.conversationEngine.endSession() }
        onFinished()
    }

    private fun wrapUp() {
        ended = true
        suppressNextErrors = true
        app.speechListener.stopListening()
        status = SessionStatus.THINKING
        scope.launch {
            try {
                val farewell = app.conversationEngine.wrapUp()
                speakFarewell(farewell)
            } catch (_: Exception) {
                finish()
            }
        }
    }

    private fun speakFarewell(text: String) {
        aiText = text
        status = SessionStatus.SPEAKING
        app.ttsManager.speak(text) {
            finish()
        }
    }

    private fun finish() {
        ended = true
        suppressNextErrors = true
        app.speechListener.stopListening()
        app.ttsManager.stop()
        status = SessionStatus.ENDED
        scope.launch { app.conversationEngine.endSession() }
        onFinished()
    }

    private fun fail(e: Exception) {
        ended = true
        suppressNextErrors = true
        app.speechListener.stopListening()
        app.ttsManager.stop()
        errorText = when {
            e is ApiException && e.message == "no api key" -> app.getString(R.string.no_api_key)
            e is ApiException && e.message == "invalid api key" -> app.getString(R.string.invalid_api_key)
            e is ApiException && e.message == "ssl error" -> app.getString(R.string.ssl_error)
            e is ApiException && e.message == "network error" -> app.getString(R.string.network_error)
            else -> app.getString(R.string.ai_error)
        }
        status = SessionStatus.ENDED
        scope.launch { app.conversationEngine.endSession() }
    }

    private fun isTimeUp(): Boolean = SystemClock.elapsedRealtime() - sessionStart >= sessionMs
}
