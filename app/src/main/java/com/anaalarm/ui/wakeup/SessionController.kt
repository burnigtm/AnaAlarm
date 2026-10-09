package com.anaalarm.ui.wakeup

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.ai.ApiException
import com.anaalarm.ai.stream.StreamingTurnCoordinator
import com.anaalarm.alarm.DismissalChallenges
import com.anaalarm.ui.avatar.Avatars
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

/** What the user must complete before the Stop button takes effect. */
data class StopChallengeUi(
    val type: DismissalChallenges.Type,
    /** Math variant: the question to answer. */
    val mathQuestion: DismissalChallenges.MathQuestion? = null,
    /** Memory variant: true while the code is still visible. */
    val showingCode: Boolean = false
)

/** Wake-session façade; listening, model turns, and dismissal retain separate owners. */
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
    /** Non-null while a stop challenge is on screen; Stop only fires once it is solved. */
    var activeChallenge by mutableStateOf<StopChallengeUi?>(null)
        private set
    var conversationFailed by mutableStateOf(false)
        private set
    var retryAvailable by mutableStateOf(false)
        private set
    var snoozeMinutes by mutableStateOf(10)
        private set
    var snoozesRemaining by mutableStateOf<Int?>(null)
        private set
    var voiceAvailable by mutableStateOf(voiceAvailable)
        private set
    var buddyReaction by mutableStateOf<com.anaalarm.ui.avatar.AvatarMood?>(null)
        private set
    var typedDraft by mutableStateOf("")
    val canInterrupt: Boolean get() = !ended && !ending && !disposed && activeChallenge == null &&
        (status == SessionStatus.SPEAKING || status == SessionStatus.THINKING) && !conversationFailed
    val canRepeat: Boolean get() = !ended && !ending && !disposed && activeChallenge == null &&
        status == SessionStatus.LISTENING && aiText.isNotBlank()
    /** Species of the animated wake-up buddy rendered beside the conversation. */
    var buddy by mutableStateOf(Avatars.DEFAULT)
        private set

    val snoozeAvailable: Boolean get() = alarmId >= 1L

    /** When true the UI shows a text field for answers (mic missing or unproductive). */
    var textInputActive by mutableStateOf(!voiceAvailable)
        private set

    private var languageTag = "en-US"
    private var sessionMs = 10 * 60_000L
    private var streamingEnabled = false
    private var stopChallengeType = DismissalChallenges.Type.NONE
    /** True once TTS produced audible output this session; gates the offline farewell. */
    private var everSpoken = false
    private val sessionStart = SystemClock.elapsedRealtime()
    private var started = false
    private var ended = false
    private var ending = false
    private var disposed = false
    private var silentStreak = 0
    private var turnInFlight = false
    private var voiceGeneration = 0L
    private var ttsGeneration = 0L
    private var turnGeneration = 0L
    private var stopPolicyReady = alarmId < 1L
    private var pendingStopRequest = false

    private var startupJob: Job? = null
    private var deadlineJob: Job? = null
    private var boundSpeechListener: SpeechListener? = null
    private var activeCoordinator: StreamingTurnCoordinator? = null

    private val finalizationStarted = AtomicBoolean(false)
    private val finishedCallbackSent = AtomicBoolean(false)
    private val alarmFallbackStopped = AtomicBoolean(false)
    private val conversationEngine = app.newConversationEngine()

    private lateinit var listen: SessionListenCycle
    private lateinit var turns: SessionTurnPipeline
    private lateinit var dismissal: SessionDismissal

    private val speechResultCallback: (String) -> Unit = { turns.onUserSpeech(it) }
    private val speechPartialCallback: (String) -> Unit = { listen.onPartialSpeech(it) }
    private val speechErrorCallback: (Int) -> Unit = { listen.onSpeechError(it) }

    init {
        listen = SessionListenCycle(
            app = app,
            scope = scope,
            host = object : SessionListenCycle.Host {
                override fun isTerminal() = ended || ending || disposed || activeChallenge != null || conversationFailed
                override fun isTextInputActive() = textInputActive
                override var status: SessionStatus
                    get() = this@SessionController.status
                    set(value) { this@SessionController.status = value }
                override var partialUserText: String
                    get() = this@SessionController.partialUserText
                    set(value) { this@SessionController.partialUserText = value }
                override var voiceHint: String?
                    get() = this@SessionController.voiceHint
                    set(value) { this@SessionController.voiceHint = value }
                override var silentStreak: Int
                    get() = this@SessionController.silentStreak
                    set(value) { this@SessionController.silentStreak = value }
                override fun voiceGeneration() = voiceGeneration
                override fun nextVoiceGeneration() = ++voiceGeneration
                override fun isCurrentVoiceGeneration(generation: Long) =
                    generation == voiceGeneration && !ended && !ending && !disposed && activeChallenge == null && !conversationFailed
                override fun wrapUp() = this@SessionController.wrapUp()
                override fun switchToTextInput(speakPrompt: Boolean) =
                    this@SessionController.switchToTextInput(speakPrompt)
                override fun speak(text: String) = this@SessionController.speak(text)
                override fun languageTag() = languageTag
                override fun isTimeUp() = this@SessionController.isTimeUp()
            }
        )

        turns = SessionTurnPipeline(
            app = app,
            scope = scope,
            conversationEngine = conversationEngine,
            host = object : SessionTurnPipeline.Host {
                override fun isTerminal() = ended || ending || disposed || activeChallenge != null || conversationFailed
                override var status: SessionStatus
                    get() = this@SessionController.status
                    set(value) { this@SessionController.status = value }
                override var lastUserText: String
                    get() = this@SessionController.lastUserText
                    set(value) { this@SessionController.lastUserText = value }
                override var partialUserText: String
                    get() = this@SessionController.partialUserText
                    set(value) { this@SessionController.partialUserText = value }
                override var voiceHint: String?
                    get() = this@SessionController.voiceHint
                    set(value) { this@SessionController.voiceHint = value }
                override var aiText: String
                    get() = this@SessionController.aiText
                    set(value) { this@SessionController.aiText = value }
                override var silentStreak: Int
                    get() = this@SessionController.silentStreak
                    set(value) { this@SessionController.silentStreak = value }
                override var turnInFlight: Boolean
                    get() = this@SessionController.turnInFlight
                    set(value) { this@SessionController.turnInFlight = value }
                override var everSpoken: Boolean
                    get() = this@SessionController.everSpoken
                    set(value) { this@SessionController.everSpoken = value }
                override var activeCoordinator: StreamingTurnCoordinator?
                    get() = this@SessionController.activeCoordinator
                    set(value) { this@SessionController.activeCoordinator = value }
                override fun nextTurnGeneration() = ++turnGeneration
                override fun currentTurnGeneration() = turnGeneration
                override fun requestStop() = this@SessionController.requestStop()
                override fun streamingEnabled() = streamingEnabled
                override fun cancelListenCycle(stopRecognizer: Boolean) =
                    listen.cancelListenCycle(stopRecognizer)
                override fun wrapUp() = this@SessionController.wrapUp()
                override fun speak(text: String) = this@SessionController.speak(text)
                override fun afterSpeaking() = this@SessionController.afterSpeaking()
                override fun stopAlarmFallback() = this@SessionController.stopAlarmFallback()
                override fun fail(e: Exception) = this@SessionController.fail(e)
            }
        )

        dismissal = SessionDismissal(
            app = app,
            scope = scope,
            alarmId = alarmId,
            conversationEngine = conversationEngine,
            host = object : SessionDismissal.Host {
                override fun isEnded() = ended
                override fun isEnding() = ending
                override fun isDisposed() = disposed
                override fun markEnding() { ending = true }
                override fun transitionToEnded() = this@SessionController.transitionToEnded()
                override var status: SessionStatus
                    get() = this@SessionController.status
                    set(value) { this@SessionController.status = value }
                override var aiText: String
                    get() = this@SessionController.aiText
                    set(value) { this@SessionController.aiText = value }
                override var errorText: String?
                    get() = this@SessionController.errorText
                    set(value) { this@SessionController.errorText = value }
                override var snoozeInFlight: Boolean
                    get() = this@SessionController.snoozeInFlight
                    set(value) { this@SessionController.snoozeInFlight = value }
                override var activeChallenge: StopChallengeUi?
                    get() = this@SessionController.activeChallenge
                    set(value) { this@SessionController.activeChallenge = value }
                override var everSpoken: Boolean
                    get() = this@SessionController.everSpoken
                    set(value) { this@SessionController.everSpoken = value }
                override fun pauseForChallenge() = this@SessionController.pauseForChallenge()
                override fun resumeAfterChallenge() = this@SessionController.resumeAfterChallenge()
                override fun requestStop() = this@SessionController.requestStop()
                override fun stopChallengeType() = this@SessionController.stopChallengeType
                override fun cancelListenCycle(stopRecognizer: Boolean) =
                    listen.cancelListenCycle(stopRecognizer)
                override fun cancelNetworkJobs() = this@SessionController.cancelNetworkJobs()
                override fun nextTtsGeneration() = ++ttsGeneration
                override fun currentTtsGeneration() = ttsGeneration
                override fun stopAlarmFallback() = this@SessionController.stopAlarmFallback()
                override fun onFinishedOnce() {
                    if (finishedCallbackSent.compareAndSet(false, true)) onFinished()
                }
                override fun finalizeConversation() = this@SessionController.finalizeConversation()
            }
        )
    }

    companion object {
        private const val TAG = "AnaSession"

        /**
         * The configured session length is a soft target checked between turns; this grace window
         * is the hard wall-clock cap. At the deadline the session wraps up even if a THINKING
         * turn or a chatty exchange would otherwise keep it alive indefinitely.
         */
        private const val HARD_DEADLINE_GRACE_MS = 90_000L
    }

    fun start() {
        if (started || disposed) return
        started = true
        bindSpeechCallbacks(app.speechListener)
        startupJob = scope.launch {
            try {
                val alarm = app.memoryStore.getAlarm(alarmId)
                stopChallengeType = DismissalChallenges.Type.from(alarm?.challengeType ?: 0)
                alarm?.let { snoozeMinutes = it.snoozeMinutes }
                stopPolicyReady = true
                if (pendingStopRequest) {
                    pendingStopRequest = false
                    requestStop()
                    return@launch
                }
                val settings = app.settingsStore.settings.first()
                languageTag = if (settings.language == "pt") "pt-BR" else "en-US"
                sessionMs = settings.sessionMinutes * 60_000L
                streamingEnabled = settings.streamingEnabled
                buddy = Avatars.from(settings.avatar)
                snoozesRemaining = app.alarmScheduler.remainingSnoozes(alarmId)
                scheduleHardDeadline()

                // TTS binding and the first network request are independent cold-start costs.
                // Starting both now removes up to an entire TTS initialization from greeting
                // latency while the local alarm remains the audible fallback.
                val (greetingText, ready) = supervisorScope {
                    val ttsReady = async {
                        runCatching { prepareTts(settings) }.getOrDefault(false)
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
                stopPolicyReady = true
                if (pendingStopRequest) {
                    pendingStopRequest = false
                    stopNow()
                    return@launch
                }
                Log.e(TAG, "beginSession failed", e)
                fail(e)
            }
        }
    }

    private fun scheduleHardDeadline() {
        deadlineJob?.cancel()
        deadlineJob = scope.launch {
            delay((sessionMs + HARD_DEADLINE_GRACE_MS - (SystemClock.elapsedRealtime() - sessionStart)).coerceAtLeast(0L))
            if (!ended && !ending && !disposed) {
                Log.i(TAG, "Hard session deadline reached; wrapping up")
                wrapUp()
            }
        }
    }

    private suspend fun prepareTts(settings: com.anaalarm.data.AppSettings): Boolean {
        var manager = app.ttsManager
        var ready = manager.awaitReady(3_000L)
        if (!ready && !ended && !disposed) {
            Log.w(TAG, "TTS not ready; recreating engine once")
            app.recreateTts()
            manager = app.ttsManager
            ready = manager.awaitReady(5_000L)
        }
        if (ready && !ended && !disposed) {
            manager.setLanguage(settings.language)
            manager.setPitch(settings.voicePitch)
            manager.setSpeechRate(settings.voiceRate)
        }
        return ready
    }

    private fun prepareSpeechRecognizer() {
        if (!app.speechListener.isAvailable) app.recreateSpeech()
        bindSpeechCallbacks(app.speechListener)
        voiceAvailable = app.speechListener.isAvailable && androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
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

    fun setTextInput(active: Boolean) {
        if (ended || ending || disposed || activeChallenge != null || conversationFailed) return
        if (!active && !voiceAvailable) return
        textInputActive = active
        if (status == SessionStatus.LISTENING) {
            cancelListenCycle(stopRecognizer = true)
            if (active || !alarmFallbackStopped.get() && alarmId >= 1L) {
                textInputActive = true
                voiceHint = app.getString(R.string.type_fallback_hint)
            } else startListeningAttempt()
        }
    }

    fun interrupt() {
        if (!canInterrupt) return
        ++turnGeneration
        ++ttsGeneration
        turns.cancel()
        activeCoordinator?.dispose()
        activeCoordinator = null
        app.ttsManager.stop()
        turnInFlight = false
        cancelListenCycle(stopRecognizer = true)
        status = SessionStatus.LISTENING
        if (textInputActive || !voiceAvailable || alarmId >= 1L && !alarmFallbackStopped.get()) {
            textInputActive = true
            voiceHint = app.getString(R.string.type_fallback_hint)
        } else startListeningAttempt()
    }

    fun repeatReply() { if (canRepeat) speak(aiText) }

    fun reactToTap() {
        if (disposed) return
        buddyReaction = com.anaalarm.ui.avatar.AvatarMood.HAPPY
        scope.launch { delay(650L); buddyReaction = null }
    }

    fun retry() {
        if (!retryAvailable || ended || ending || disposed || turnInFlight) return
        retryAvailable = false
        conversationFailed = false
        errorText = null
        ++ttsGeneration
        app.ttsManager.stop()
        scheduleHardDeadline()
        if (lastUserText.isBlank()) {
            started = false
            status = SessionStatus.STARTING
            start()
        } else {
            status = SessionStatus.LISTENING
            onUserSpeech(lastUserText)
        }
    }

    private fun speak(text: String) {
        if (ended || ending || disposed || activeChallenge != null) return
        aiText = text
        status = SessionStatus.THINKING
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
                status = SessionStatus.SPEAKING
                everSpoken = true
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

    private fun afterSpeaking() = listen.scheduleAfterSpeaking()

    private fun startListeningAttempt() = listen.startListeningAttempt()

    private fun onUserSpeech(text: String) = turns.onUserSpeech(text)

    /**
     * Streaming turn: phrases are spoken while the model is still generating. The microphone
     * reopens only after the semantic terminal AND the final utterance completion, which is the
     * point where [coordinator.awaitSettled] returns.
     */

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
                if (generation == ttsGeneration && !ended && !disposed) { everSpoken = true; stopAlarmFallback() }
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

    /** Returns false when the input was rejected, so the UI can keep it for another attempt. */
    fun submitText(text: String): Boolean {
        if (text.isBlank() || ended || ending || disposed) return false
        // Typed input is accepted from the same visible LISTENING state as speech.
        if (status != SessionStatus.LISTENING || activeChallenge != null) return false
        onUserSpeech(text.trim())
        return true
    }

    fun stopNow() = dismissal.stopNow()

    /**
     * Stop-button entry point: alarms configured with a dismissal challenge must prove the
     * user is awake first. Test sessions (no alarm id) always stop immediately. An already-
     * ended session (e.g. after a cold-start AI failure) must still be dismissible, so the
     * explicit path bypasses the ended guard — the screen would otherwise be stuck open.
     */
    fun requestStop() {
        if (disposed) return
        if (!stopPolicyReady) { pendingStopRequest = true; return }
        dismissal.requestStop()
    }

    private fun pauseForChallenge() {
        ++turnGeneration
        ++ttsGeneration
        startupJob?.cancel()
        turns.cancel()
        activeCoordinator?.dispose()
        activeCoordinator = null
        app.ttsManager.stop()
        cancelListenCycle(stopRecognizer = true)
        deadlineJob?.cancel()
        turnInFlight = false
    }

    private fun resumeAfterChallenge() {
        if (ended || ending || disposed) return
        if (conversationFailed) { status = SessionStatus.ENDED; return }
        if (!conversationEngine.sessionStarted) {
            started = false
            status = SessionStatus.STARTING
            start()
            return
        }
        scheduleHardDeadline()
        status = SessionStatus.LISTENING
        if (textInputActive) voiceHint = app.getString(R.string.type_fallback_hint)
        else startListeningAttempt()
    }

    /** Memory challenge: the user saw the code and is ready to type it back. */
    fun beginChallengeAnswer() = dismissal.beginChallengeAnswer()

    /**
     * Returns true when the answer unlocked Stop (the session then terminates); a wrong
     * answer regenerates the math question or keeps the code prompt open.
     */
    fun submitChallengeAnswer(typed: String): Boolean = dismissal.submitChallengeAnswer(typed)

    /** Called after the challenge-success pose has had a moment on screen. */
    fun completeChallengeStop() = dismissal.completeChallengeStop()

    fun dismissChallenge() = dismissal.dismissChallenge()

    /** Display-only access for the memory challenge's visible-code phase. */
    fun revealMemoryCodeForDisplay(): String = dismissal.revealMemoryCodeForDisplay()

    fun snoozeNow() = dismissal.snoozeNow()

    private fun wrapUp() = dismissal.wrapUp()

    private fun fail(e: Exception) {
        if (ended || disposed) return
        ++turnGeneration
        conversationFailed = true
        retryAvailable = !(e is ApiException && e.message in setOf("no api key", "invalid api key"))
        cancelListenCycle(stopRecognizer = true)
        deadlineJob?.cancel()
        errorText = when {
            e is ApiException && e.message == "no api key" -> app.getString(R.string.no_api_key)
            e is ApiException && e.message == "invalid api key" -> app.getString(R.string.invalid_api_key)
            e is ApiException && e.message == "ssl error" -> app.getString(R.string.ssl_error)
            e is ApiException && e.message == "network error" -> app.getString(R.string.network_error)
            else -> app.getString(R.string.ai_error)
        }
        if (app.ttsManager.isReady && everSpoken) {
            // The conversation already produced audible speech, so a localized spoken wrap-up
            // fits naturally. Its audible start silences the alarm (invariant 5); completion
            // only marks the conversation ended — Stop/Snooze stay available. A cold-start
            // failure (never spoken, e.g. missing key) keeps the alarm ringing instead.
            speakOfflineFarewell()
        } else {
            // Keep the independent alarm service ringing. Only audible TTS or an explicit user
            // Stop/Snooze is allowed to silence a wake-up after an API/offline failure.
            status = SessionStatus.ENDED
        }
    }

    private fun speakOfflineFarewell() {
        status = SessionStatus.SPEAKING
        val generation = ++ttsGeneration
        app.ttsManager.speak(
            text = app.getString(R.string.offline_farewell),
            onStart = {
                if (generation == ttsGeneration && !ended && !disposed) stopAlarmFallback()
            },
            completion = {
                if (generation == ttsGeneration && !ended && !disposed) {
                    status = SessionStatus.ENDED
                }
            }
        )
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
        dismissal.clearChallengeState()
        cancelNetworkJobs()
        cancelListenCycle(stopRecognizer = true)
        ++ttsGeneration
        app.ttsManager.stop()
        partialUserText = ""
        status = SessionStatus.ENDED
        finalizeConversation()
    }

    private fun cancelListenCycle(stopRecognizer: Boolean) = listen.cancelListenCycle(stopRecognizer)

    private fun cancelNetworkJobs() {
        startupJob?.cancel()
        startupJob = null
        turns.cancel()
        dismissal.cancelJobs()
        deadlineJob?.cancel()
        deadlineJob = null
        activeCoordinator?.dispose()
        activeCoordinator = null
        snoozeInFlight = false
        turnInFlight = false
    }

    private fun stopAlarmFallback() = SessionDismissal.stopAlarmService(app, alarmId, alarmFallbackStopped)

    private fun finalizeConversation() {
        if (!finalizationStarted.compareAndSet(false, true)) return
        val engine = conversationEngine
        app.applicationScope.launch { engine.endSession() }
    }

    private fun isTimeUp(): Boolean = SystemClock.elapsedRealtime() - sessionStart >= sessionMs
}
