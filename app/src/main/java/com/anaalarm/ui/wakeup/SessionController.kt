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
import com.anaalarm.alarm.AlarmScheduleResult
import com.anaalarm.alarm.AlarmService
import com.anaalarm.voice.SpeechListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicBoolean

enum class SessionStatus { STARTING, SPEAKING, LISTENING, THINKING, ENDED }

class SessionController(
    private val app: AnaAlarmApp,
    private val scope: CoroutineScope,
    private val onFinished: () -> Unit,
    voiceAvailable: Boolean,
    private val alarmId: Long = -1L
) {

    var status by mutableStateOf(SessionStatus.STARTING)
        private set
    var aiText by mutableStateOf("")
        private set
    var lastUserText by mutableStateOf("")
        private set
    var partialUserText by mutableStateOf("")
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var voiceHint by mutableStateOf<String?>(null)
        private set
    var snoozeInFlight by mutableStateOf(false)
        private set

    val snoozeAvailable: Boolean get() = alarmId >= 1L

    /** When true the UI shows a text field for answers (mic missing or unproductive). */
    var textInputActive by mutableStateOf(!voiceAvailable)
        private set

    private var languageTag = "en-US"
    private var sessionMs = 10 * 60_000L
    private val sessionStart = SystemClock.elapsedRealtime()
    private var started = false
    private var ended = false
    private var ending = false
    private var disposed = false
    private var silentStreak = 0
    private var turnInFlight = false
    private var voiceGeneration = 0L
    private var ttsGeneration = 0L

    private var startupJob: Job? = null
    private var turnJob: Job? = null
    private var wrapUpJob: Job? = null
    private var snoozeJob: Job? = null
    private var settleJob: Job? = null
    private var listenTimeoutJob: Job? = null
    private var listenRetryJob: Job? = null
    private var boundSpeechListener: SpeechListener? = null

    private val finalizationStarted = AtomicBoolean(false)
    private val finishedCallbackSent = AtomicBoolean(false)
    private val alarmFallbackStopped = AtomicBoolean(false)
    private val conversationEngine = app.newConversationEngine()

    private val speechResultCallback: (String) -> Unit = { onUserSpeech(it) }
    private val speechPartialCallback: (String) -> Unit = { onPartialSpeech(it) }
    private val speechErrorCallback: (Int) -> Unit = { onSpeechError(it) }

    companion object {
        private const val TAG = "AnaSession"
        private const val LISTEN_TIMEOUT_MS = 25_000L
        private const val MIC_SETTLE_MS = 175L
        private const val LISTEN_RETRY_MS = 350L
        private const val MAX_SILENT_CYCLES = 2
    }

    fun start() {
        if (started || disposed) return
        started = true
        bindSpeechCallbacks(app.speechListener)
        startupJob = scope.launch {
            try {
                val settings = app.settingsStore.settings.first()
                languageTag = if (settings.language == "pt") "pt-BR" else "en-US"
                sessionMs = settings.sessionMinutes * 60_000L

                // TTS binding and the first network request are independent cold-start costs.
                // Starting both now removes up to an entire TTS initialization from greeting
                // latency while the local alarm remains the audible fallback.
                val (greetingText, ready) = supervisorScope {
                    val ttsReady = async {
                        runCatching { prepareTts(settings.language) }.getOrDefault(false)
                    }
                    val greeting = async { conversationEngine.startSession() }

                    prepareSpeechRecognizer()
                    val greetingText = greeting.await()
                    if (!ended && !disposed) aiText = greetingText
                    greetingText to ttsReady.await()
                }
                if (ended || disposed) return@launch
                Log.i(
                    TAG,
                    "startup ttsReady=$ready speechAvailable=${app.speechListener.isAvailable}"
                )
                if (ready) {
                    speak(greetingText)
                } else {
                    // Never open the microphone while the alarm fallback is still ringing.
                    activateSilentTtsFallback()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "beginSession failed", e)
                fail(e)
            }
        }
    }

    private suspend fun prepareTts(language: String): Boolean {
        var manager = app.ttsManager
        var ready = manager.awaitReady(3_000L)
        if (!ready && !ended && !disposed) {
            Log.w(TAG, "TTS not ready; recreating engine once")
            app.recreateTts()
            manager = app.ttsManager
            ready = manager.awaitReady(5_000L)
        }
        if (ready && !ended && !disposed) {
            manager.setLanguage(language)
            manager.setPitch(1.05f)
            manager.setSpeechRate(1.0f)
        }
        return ready
    }

    private fun prepareSpeechRecognizer() {
        if (!app.speechListener.isAvailable) app.recreateSpeech()
        bindSpeechCallbacks(app.speechListener)
        if (!textInputActive && !app.speechListener.isAvailable) {
            switchToTextInput(speakPrompt = false)
        }
    }

    private fun bindSpeechCallbacks(listener: SpeechListener) {
        detachSpeechCallbacks()
        boundSpeechListener = listener
        listener.onResult = speechResultCallback
        listener.onPartialResult = speechPartialCallback
        listener.onError = speechErrorCallback
    }

    private fun detachSpeechCallbacks() {
        boundSpeechListener?.let { listener ->
            if (listener.onResult === speechResultCallback) listener.onResult = null
            if (listener.onPartialResult === speechPartialCallback) listener.onPartialResult = null
            if (listener.onError === speechErrorCallback) listener.onError = null
        }
        boundSpeechListener = null
    }

    fun enableTextInput() {
        if (status != SessionStatus.LISTENING || ended || ending || disposed) return
        switchToTextInput(speakPrompt = false)
    }

    private fun speak(text: String) {
        if (ended || ending || disposed) return
        aiText = text
        status = SessionStatus.SPEAKING
        voiceHint = null
        partialUserText = ""
        cancelListenCycle(stopRecognizer = true)
        val generation = ++ttsGeneration
        var startedAudibly = false
        app.ttsManager.speak(
            text = text,
            onStart = {
                if (generation != ttsGeneration || ended || disposed) return@speak
                startedAudibly = true
                stopAlarmFallback()
            },
            completion = {
                if (generation != ttsGeneration || ended || disposed) return@speak
                if (!startedAudibly && !alarmFallbackStopped.get()) {
                    activateSilentTtsFallback()
                } else {
                    afterSpeaking()
                }
            }
        )
    }

    private fun afterSpeaking() {
        if (ended || ending || disposed) return
        if (isTimeUp()) {
            wrapUp()
            return
        }
        val generation = ++voiceGeneration
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(MIC_SETTLE_MS)
            if (!isCurrentVoiceGeneration(generation)) return@launch
            status = SessionStatus.LISTENING
            if (textInputActive) {
                voiceHint = app.getString(R.string.type_fallback_hint)
            } else {
                startListeningAttempt()
            }
        }
    }

    private fun startListeningAttempt() {
        if (ended || ending || disposed || textInputActive) return
        if (!app.speechListener.isAvailable) {
            switchToTextInput(speakPrompt = true)
            return
        }
        settleJob?.cancel()
        listenTimeoutJob?.cancel()
        listenRetryJob?.cancel()
        val generation = ++voiceGeneration
        status = SessionStatus.LISTENING
        partialUserText = ""
        voiceHint = app.getString(R.string.status_listening)
        app.speechListener.startListening(languageTag)
        listenTimeoutJob = scope.launch {
            delay(LISTEN_TIMEOUT_MS)
            if (!isCurrentVoiceGeneration(generation) || textInputActive) return@launch
            app.speechListener.stopListening()
            silentStreak++
            when {
                isTimeUp() -> wrapUp()
                silentStreak >= MAX_SILENT_CYCLES -> switchToTextInput(speakPrompt = true)
                else -> speak(app.getString(R.string.did_not_catch))
            }
        }
    }

    private fun onPartialSpeech(text: String) {
        if (ended || ending || disposed || status != SessionStatus.LISTENING) return
        partialUserText = text
    }

    private fun onUserSpeech(text: String) {
        if (ended || ending || disposed || status != SessionStatus.LISTENING) return
        cancelListenCycle(stopRecognizer = true)
        lastUserText = text
        partialUserText = ""
        silentStreak = 0
        voiceHint = null
        Log.i(TAG, "Received user speech len=${text.length}")
        if (SessionPhrases.isStopPhrase(text)) {
            wrapUp()
            return
        }
        if (turnInFlight) return
        turnInFlight = true
        status = SessionStatus.THINKING
        turnJob = scope.launch {
            try {
                val reply = conversationEngine.respond(text)
                turnInFlight = false
                if (!ended && !ending && !disposed) speak(reply)
            } catch (e: CancellationException) {
                turnInFlight = false
                throw e
            } catch (e: Exception) {
                turnInFlight = false
                fail(e)
            }
        }
    }

    private fun onSpeechError(code: Int) {
        if (ended || ending || disposed || status != SessionStatus.LISTENING) return
        listenTimeoutJob?.cancel()
        listenTimeoutJob = null
        partialUserText = ""

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
        val generation = voiceGeneration
        listenRetryJob?.cancel()
        listenRetryJob = scope.launch {
            delay(LISTEN_RETRY_MS)
            if (isCurrentVoiceGeneration(generation) && !textInputActive) {
                startListeningAttempt()
            }
        }
    }

    private fun switchToTextInput(speakPrompt: Boolean) {
        if (ended || ending || disposed) return
        cancelListenCycle(stopRecognizer = true)
        textInputActive = true
        partialUserText = ""
        voiceHint = app.getString(R.string.type_fallback_switch)
        status = SessionStatus.LISTENING
        if (speakPrompt && app.ttsManager.isReady) speakTextFallbackPrompt()
    }

    private fun speakTextFallbackPrompt() {
        val generation = ++ttsGeneration
        status = SessionStatus.SPEAKING
        app.ttsManager.speak(
            text = app.getString(R.string.type_fallback_switch),
            onStart = {
                if (generation == ttsGeneration && !ended && !disposed) stopAlarmFallback()
            },
            completion = {
                if (generation == ttsGeneration && !ended && !ending && !disposed) {
                    status = SessionStatus.LISTENING
                    voiceHint = app.getString(R.string.type_fallback_switch)
                }
            }
        )
    }

    private fun activateSilentTtsFallback() {
        if (ended || ending || disposed) return
        cancelListenCycle(stopRecognizer = true)
        textInputActive = true
        partialUserText = ""
        status = SessionStatus.LISTENING
        voiceHint = app.getString(R.string.type_fallback_switch)
    }

    fun submitText(text: String) {
        if (text.isBlank() || ended || ending || disposed) return
        // Typed input is accepted from the same visible LISTENING state as speech.
        if (status != SessionStatus.LISTENING) return
        onUserSpeech(text.trim())
    }

    fun stopNow() {
        terminateSession(finishActivity = true)
    }

    fun snoozeNow() {
        if (!snoozeAvailable || snoozeInFlight || ending || disposed) return
        snoozeInFlight = true
        errorText = null
        snoozeJob = scope.launch {
            val result = runCatching { app.alarmScheduler.scheduleSnooze(alarmId) }
                .getOrElse {
                    AlarmScheduleResult.Failed(
                        AlarmScheduleResult.Failed.Reason.SYSTEM_ERROR,
                        it
                    )
                }
            snoozeInFlight = false
            snoozeJob = null
            if (result is AlarmScheduleResult.Scheduled) {
                terminateSession(finishActivity = true)
            } else if (!disposed) {
                // Scheduling failure is recoverable. Keep the existing session (and its alarm
                // fallback) active so the user can retry or explicitly stop it.
                errorText = app.getString(R.string.snooze_failed)
            }
        }
    }

    private fun wrapUp() {
        if (ended || ending || disposed) return
        ending = true
        cancelListenCycle(stopRecognizer = true)
        status = SessionStatus.THINKING
        wrapUpJob = scope.launch {
            try {
                val farewell = conversationEngine.wrapUp()
                if (!ended && !disposed) speakFarewell(farewell)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!ended && !disposed) terminateSession(finishActivity = true)
            }
        }
    }

    private fun speakFarewell(text: String) {
        if (ended || disposed) return
        aiText = text
        status = SessionStatus.SPEAKING
        val generation = ++ttsGeneration
        app.ttsManager.speak(
            text = text,
            onStart = {
                if (generation == ttsGeneration && !ended && !disposed) stopAlarmFallback()
            },
            completion = {
                if (generation == ttsGeneration && !ended && !disposed) {
                    terminateSession(finishActivity = true)
                }
            }
        )
    }

    private fun terminateSession(finishActivity: Boolean) {
        transitionToEnded()
        // Explicit Stop/Snooze must silence the service even when the AI session already failed.
        stopAlarmFallback()
        if (finishActivity && finishedCallbackSent.compareAndSet(false, true)) onFinished()
    }

    private fun fail(e: Exception) {
        if (ended || disposed) return
        errorText = when {
            e is ApiException && e.message == "no api key" -> app.getString(R.string.no_api_key)
            e is ApiException && e.message == "invalid api key" -> app.getString(R.string.invalid_api_key)
            e is ApiException && e.message == "ssl error" -> app.getString(R.string.ssl_error)
            e is ApiException && e.message == "network error" -> app.getString(R.string.network_error)
            else -> app.getString(R.string.ai_error)
        }
        // Keep the independent alarm service ringing. Only audible TTS or an explicit user
        // Stop/Snooze is allowed to silence a wake-up after an API/offline failure.
        transitionToEnded()
    }

    /** Clears callbacks/jobs owned by this Activity. Final persistence survives Activity teardown. */
    fun dispose(keepAlarmFallback: Boolean = false) {
        if (disposed) return
        transitionToEnded()
        disposed = true
        detachSpeechCallbacks()
        cancelNetworkJobs()
        cancelListenCycle(stopRecognizer = true)
        ++ttsGeneration
        app.ttsManager.stop()
        if (!keepAlarmFallback) stopAlarmFallback()
        finalizeConversation()
    }

    private fun transitionToEnded() {
        if (ended) return
        ended = true
        ending = false
        cancelNetworkJobs()
        cancelListenCycle(stopRecognizer = true)
        ++ttsGeneration
        app.ttsManager.stop()
        partialUserText = ""
        status = SessionStatus.ENDED
        finalizeConversation()
    }

    private fun cancelListenCycle(stopRecognizer: Boolean) {
        ++voiceGeneration
        settleJob?.cancel()
        settleJob = null
        listenTimeoutJob?.cancel()
        listenTimeoutJob = null
        listenRetryJob?.cancel()
        listenRetryJob = null
        if (stopRecognizer) app.speechListener.stopListening()
    }

    private fun cancelNetworkJobs() {
        startupJob?.cancel()
        startupJob = null
        turnJob?.cancel()
        turnJob = null
        wrapUpJob?.cancel()
        wrapUpJob = null
        snoozeJob?.cancel()
        snoozeJob = null
        snoozeInFlight = false
        turnInFlight = false
    }

    private fun isCurrentVoiceGeneration(generation: Long): Boolean =
        generation == voiceGeneration && !ended && !ending && !disposed

    private fun stopAlarmFallback() {
        if (alarmFallbackStopped.compareAndSet(false, true)) {
            runCatching { AlarmService.stop(app) }
                .onFailure { Log.w(TAG, "Unable to stop alarm fallback", it) }
        }
    }

    private fun finalizeConversation() {
        if (!finalizationStarted.compareAndSet(false, true)) return
        val engine = conversationEngine
        app.applicationScope.launch { engine.endSession() }
    }

    private fun isTimeUp(): Boolean = SystemClock.elapsedRealtime() - sessionStart >= sessionMs
}
