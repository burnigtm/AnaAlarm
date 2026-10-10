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
    private val alarmId: Long = -1L,
    private val timing: SessionTiming = SessionTiming()
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
    val canInterrupt: Boolean get() = !isClosing() && activeChallenge == null &&
        (status == SessionStatus.SPEAKING || status == SessionStatus.THINKING) && !conversationFailed
    val canRepeat: Boolean get() = !isClosing() && activeChallenge == null &&
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
    private val sessionStart = timing.nowMillis()
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
    private var boundSpeechOutput: com.anaalarm.voice.SpeechOutput? = null
    private val focusLostCallback: () -> Unit = {
        if (!isClosing() && activeChallenge == null) {
            textInputActive = true
            interrupt()
            if (status == SessionStatus.LISTENING) {
                cancelListenCycle(true)
                voiceHint = app.getString(R.string.mic_error)
                react(com.anaalarm.ui.avatar.AvatarMood.REASSURING)
            }
        }
    }
    private var boundSpeechListener: com.anaalarm.voice.SpeechInput? = null
    private var activeCoordinator: StreamingTurnCoordinator? = null

    private val finalizationStarted = AtomicBoolean(false)
    private val finishedCallbackSent = AtomicBoolean(false)
    private val alarmFallbackStopped = AtomicBoolean(false)
    private val conversationEngine = app.newConversationEngine()

    private lateinit var listen: SessionListenCycle
    private lateinit var turns: SessionTurnPipeline
    private lateinit var dismissal: SessionDismissal

    var microphoneLevel by mutableStateOf(0f)
        private set
    var speechActive by mutableStateOf(false)
        private set
    var microphoneReady by mutableStateOf(false)
        private set
    var routineActive by mutableStateOf(false)
        private set
    var routineHabit by mutableStateOf<String?>(null)
        private set
    var routineProgress by mutableStateOf("")
        private set
    var routineFinished by mutableStateOf(false)
        private set
    var challengeSolved by mutableStateOf(false)
        private set
    var challengeWrong by mutableStateOf(false)
        private set
    private var routine: MorningRoutine? = null
    private var routineJob: Job? = null
    private var reactionJob: Job? = null
    private var challengeJob: Job? = null
    private val speechReadyCallback: () -> Unit = { if (!isClosing()) microphoneReady = true }
    private val speechActivityCallback: (Boolean) -> Unit = { active ->
        if (!isClosing()) { speechActive = active; if (active) react(com.anaalarm.ui.avatar.AvatarMood.ACKNOWLEDGING) }
    }
    private val speechLevelCallback: (Float) -> Unit = { if (!isClosing()) microphoneLevel = if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
    private val speechResultCallback: (String) -> Unit = { onUserSpeech(it) }
    private val speechPartialCallback: (String) -> Unit = {
        if (activeChallenge != null && !challengeSolved && !disposed) partialUserText = it
        else listen.onPartialSpeech(it)
    }
    private val speechErrorCallback: (Int) -> Unit = {
        microphoneReady = false; microphoneLevel = 0f; speechActive = false
        if (it == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) this.voiceAvailable = false
        if (activeChallenge != null) {
            challengeJob?.cancel()
            microphoneReady = false; microphoneLevel = 0f; speechActive = false; partialUserText = ""
            voiceHint = app.getString(R.string.mic_error)
            react(com.anaalarm.ui.avatar.AvatarMood.REASSURING)
        } else listen.onSpeechError(it)
    }

    init {
        listen = SessionListenCycle(
            app = app,
            scope = scope,
            host = object : SessionListenCycle.Host {
                override fun isTerminal() = isClosing() || activeChallenge != null || conversationFailed
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
                    generation == voiceGeneration && !isClosing() && activeChallenge == null && !conversationFailed
                override fun wrapUp() = this@SessionController.wrapUp()
                override fun switchToTextInput(speakPrompt: Boolean) =
                    this@SessionController.switchToTextInput(speakPrompt)
                override fun speak(text: String) = this@SessionController.speak(text)
                override fun reassure() = react(com.anaalarm.ui.avatar.AvatarMood.REASSURING)
                override fun languageTag() = languageTag
                override fun isTimeUp() = this@SessionController.isTimeUp()
            },
            timing = timing
        )

        turns = SessionTurnPipeline(
            app = app,
            scope = scope,
            conversationEngine = conversationEngine,
            host = object : SessionTurnPipeline.Host {
                override fun isTerminal() = isClosing() || activeChallenge != null || conversationFailed
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
                override fun resumeAfterSnoozeFailure() { if (activeChallenge == null && status == SessionStatus.LISTENING) afterSpeaking() }
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
        bindSpeechCallbacks(app.speechInput)
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

                if (settings.sessionMode == com.anaalarm.data.SessionModes.ROUTINE) {
                    routineActive = true
                    val done = app.memoryStore.habitsForDate(timing.today()).filter { it.done }.map { it.name }.toSet()
                    routine = MorningRoutine(settings.habits, done)
                    val ready = prepareTts(settings)
                    prepareSpeechRecognizer()
                    if (!ended) showRoutinePrompt(ready)
                    return@launch
                }

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
                    if (!ended) aiText = greetingText
                    greetingText to ttsReady.await()
                }
                if (ended) return@launch
                Log.i(
                    TAG,
                    "startup ttsReady=$ready speechAvailable=${app.speechInput.isAvailable}"
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
            delay((sessionMs + timing.deadlineGraceMs - (timing.nowMillis() - sessionStart)).coerceAtLeast(0L))
            if (!isClosing()) {
                Log.i(TAG, "Hard session deadline reached; wrapping up")
                wrapUp()
            }
        }
    }

    private suspend fun prepareTts(settings: com.anaalarm.data.AppSettings): Boolean {
        var manager = app.speechOutput
        var ready = manager.awaitReady(3_000L)
        if (!ready && !ended) {
            Log.w(TAG, "TTS not ready; recreating engine once")
            app.recreateTts()
            manager = app.speechOutput
            ready = manager.awaitReady(5_000L)
        }
        if (ready && !ended) {
            boundSpeechOutput?.let { if (it.onFocusLost === focusLostCallback) it.onFocusLost = null }
            boundSpeechOutput = manager
            manager.onFocusLost = focusLostCallback
            manager.setLanguage(settings.language)
            manager.setPitch(settings.voicePitch)
            manager.setSpeechRate(settings.voiceRate)
        }
        return ready
    }

    private fun prepareSpeechRecognizer() {
        if (!app.speechInput.isAvailable) app.recreateSpeech()
        bindSpeechCallbacks(app.speechInput)
        voiceAvailable = app.speechInput.isAvailable && androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!voiceAvailable) {
            textInputActive = true
            voiceHint = app.getString(R.string.type_fallback_hint)
        }
    }

    private fun bindSpeechCallbacks(listener: com.anaalarm.voice.SpeechInput) {
        detachSpeechCallbacks()
        boundSpeechListener = listener
        listener.onResult = speechResultCallback
        listener.onPartialResult = speechPartialCallback
        listener.onError = speechErrorCallback
        listener.onReady = speechReadyCallback
        listener.onSpeechActivity = speechActivityCallback
        listener.onLevel = speechLevelCallback
    }

    private fun detachSpeechCallbacks() {
        boundSpeechListener?.let { listener ->
            if (listener.onResult === speechResultCallback) listener.onResult = null
            if (listener.onPartialResult === speechPartialCallback) listener.onPartialResult = null
            if (listener.onError === speechErrorCallback) listener.onError = null
            if (listener.onReady === speechReadyCallback) listener.onReady = null
            if (listener.onSpeechActivity === speechActivityCallback) listener.onSpeechActivity = null
            if (listener.onLevel === speechLevelCallback) listener.onLevel = null
        }
        boundSpeechListener = null
    }

    fun refreshVoiceAvailability() {
        if (ended) return
        voiceAvailable = app.speechInput.isAvailable && androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!voiceAvailable) {
            cancelListenCycle(true)
            textInputActive = true
        }
    }

    fun enableTextInput() {
        if (status != SessionStatus.LISTENING || isClosing()) return
        switchToTextInput(speakPrompt = false)
    }

    fun setTextInput(active: Boolean) {
        if (isClosing() || activeChallenge != null || conversationFailed) return
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
        app.speechOutput.stop()
        turnInFlight = false
        cancelListenCycle(stopRecognizer = true)
        status = SessionStatus.LISTENING
        if (textInputActive || !voiceAvailable || alarmId >= 1L && !alarmFallbackStopped.get()) {
            textInputActive = true
            voiceHint = app.getString(R.string.type_fallback_hint)
        } else startListeningAttempt()
    }

    fun repeatReply() { if (canRepeat) speak(aiText) }

    fun reactToTap() = react(com.anaalarm.ui.avatar.AvatarMood.HAPPY)

    private fun react(mood: com.anaalarm.ui.avatar.AvatarMood) {
        if (ended) return
        reactionJob?.cancel()
        buddyReaction = mood
        reactionJob = scope.launch { delay(timing.reactionMs); buddyReaction = null }
    }

    fun retry() {
        if (!retryAvailable || isClosing() || turnInFlight) return
        retryAvailable = false
        conversationFailed = false
        errorText = null
        ++ttsGeneration
        app.speechOutput.stop()
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
        if (isClosing() || activeChallenge != null) return
        aiText = text
        status = SessionStatus.THINKING
        voiceHint = null
        partialUserText = ""
        cancelListenCycle(stopRecognizer = true)
        val generation = ++ttsGeneration
        var startedAudibly = false
        app.speechOutput.speak(
            text = text,
            onStart = {
                if (generation != ttsGeneration || ended) return@speak
                startedAudibly = true
                status = SessionStatus.SPEAKING
                everSpoken = true
                stopAlarmFallback()
            },
            completion = {
                if (generation != ttsGeneration || ended) return@speak
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

    private fun onUserSpeech(text: String) {
        if (isClosing() || challengeSolved) return
        if (activeChallenge == null && status != SessionStatus.LISTENING) return
        partialUserText = ""
        microphoneLevel = 0f; speechActive = false; microphoneReady = false
        val command = com.anaalarm.voice.VoiceCommands.parse(text, routineActive && routineHabit != null && activeChallenge == null, activeChallenge != null)
        if (command != null) {
            when (command) {
                com.anaalarm.voice.VoiceCommand.STOP -> { cancelListenCycle(true); requestStop() }
                com.anaalarm.voice.VoiceCommand.SNOOZE -> { snoozeNow(); if (activeChallenge == null && !snoozeAvailable) afterSpeaking() }
                com.anaalarm.voice.VoiceCommand.REPEAT -> if (activeChallenge != null) repeatChallengePrompt() else repeatReply()
                com.anaalarm.voice.VoiceCommand.TYPE -> { if (activeChallenge != null) stopChallengeVoice() else setTextInput(true) }
                com.anaalarm.voice.VoiceCommand.CANCEL -> dismissChallenge()
                com.anaalarm.voice.VoiceCommand.COMPLETE -> advanceRoutine(true)
                com.anaalarm.voice.VoiceCommand.SKIP -> advanceRoutine(false)
            }
            return
        }
        if (activeChallenge != null) {
            val challenge = activeChallenge ?: return
            if (challenge.showingCode) return
            val answer = com.anaalarm.voice.SpokenNumbers.parse(text, challenge.type == DismissalChallenges.Type.MEMORY)
            if (answer == null) {
                stopChallengeVoice(); challengeWrong = true
                react(com.anaalarm.ui.avatar.AvatarMood.REASSURING)
            } else submitChallengeAnswer(answer)
            return
        }
        if (routineActive) {
            if (routineHabit != null) {
                cancelListenCycle(true); speak(routineString(R.string.routine_answer_hint))
            } else saveRoutinePriority(text)
            return
        }
        react(com.anaalarm.ui.avatar.AvatarMood.ACKNOWLEDGING)
        turns.onUserSpeech(text)
    }

    private fun routineString(id: Int, vararg args: Any): String {
        val config = android.content.res.Configuration(app.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag(languageTag))
        return app.createConfigurationContext(config).getString(id, *args)
    }

    private fun showRoutinePrompt(ttsReady: Boolean = app.speechOutput.isReady) {
        val current = routine ?: return
        errorText = null
        routineHabit = current.habit
        routineFinished = current.finished
        routineProgress = routineString(R.string.routine_progress, current.index, current.total)
        val prompt = when {
            current.finished -> routineString(R.string.routine_finished)
            current.habit != null -> routineString(R.string.routine_habit_prompt, current.habit.orEmpty())
            else -> routineString(R.string.routine_priority_prompt)
        }
        if (ttsReady) speak(prompt) else { aiText = prompt; activateSilentTtsFallback() }
    }

    fun advanceRoutine(completed: Boolean) {
        val current = routine ?: return
        val habit = current.habit ?: return
        if (isClosing() || activeChallenge != null || status != SessionStatus.LISTENING || routineJob?.isActive == true) return
        cancelListenCycle(true)
        status = SessionStatus.THINKING
        routineJob = scope.launch {
            try {
                if (completed) app.memoryStore.setHabitDone(habit, timing.today(), true)
                if (isClosing() || activeChallenge != null) return@launch
                current.advance()
                react(if (completed) com.anaalarm.ui.avatar.AvatarMood.HAPPY else com.anaalarm.ui.avatar.AvatarMood.ACKNOWLEDGING)
                showRoutinePrompt()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { routineStorageFailed() }
        }
    }

    private fun saveRoutinePriority(text: String) {
        if (text.isBlank() || routineJob?.isActive == true) return
        cancelListenCycle(true)
        status = SessionStatus.THINKING
        lastUserText = text.trim()
        routineJob = scope.launch {
            try {
                app.memoryStore.savePriority(text, timing.today())
                if (isClosing() || activeChallenge != null) return@launch
                routine?.finish()
                react(com.anaalarm.ui.avatar.AvatarMood.HAPPY)
                showRoutinePrompt()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { routineStorageFailed() }
        }
    }

    private fun routineStorageFailed() {
        if (isClosing() || activeChallenge != null) return
        errorText = app.getString(R.string.routine_save_failed)
        status = SessionStatus.LISTENING
        textInputActive = true
        react(com.anaalarm.ui.avatar.AvatarMood.REASSURING)
    }

    /**
     * Streaming turn: phrases are spoken while the model is still generating. The microphone
     * reopens only after the semantic terminal AND the final utterance completion, which is the
     * point where [coordinator.awaitSettled] returns.
     */

    private fun switchToTextInput(speakPrompt: Boolean) {
        if (isClosing()) return
        cancelListenCycle(stopRecognizer = true)
        textInputActive = true
        partialUserText = ""
        voiceHint = app.getString(R.string.type_fallback_switch)
        status = SessionStatus.LISTENING
        if (speakPrompt && app.speechOutput.isReady) speakTextFallbackPrompt()
    }

    private fun speakTextFallbackPrompt() {
        val generation = ++ttsGeneration
        status = SessionStatus.SPEAKING
        app.speechOutput.speak(
            text = app.getString(R.string.type_fallback_switch),
            onStart = {
                if (generation == ttsGeneration && !ended) { everSpoken = true; stopAlarmFallback() }
            },
            completion = {
                if (generation == ttsGeneration && !isClosing()) {
                    status = SessionStatus.LISTENING
                    voiceHint = app.getString(R.string.type_fallback_switch)
                }
            }
        )
    }

    private fun activateSilentTtsFallback() {
        if (isClosing()) return
        cancelListenCycle(stopRecognizer = true)
        textInputActive = true
        partialUserText = ""
        status = SessionStatus.LISTENING
        voiceHint = app.getString(R.string.type_fallback_switch)
    }

    /** Returns false when the input was rejected, so the UI can keep it for another attempt. */
    fun submitText(text: String): Boolean {
        if (text.isBlank() || isClosing()) return false
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
        if (disposed || challengeSolved) return
        challengeSolved = false
        challengeWrong = false
        if (!stopPolicyReady) { pendingStopRequest = true; return }
        dismissal.requestStop()
    }

    private fun pauseForChallenge() {
        routineJob?.cancel(); routineJob = null
        ++turnGeneration
        ++ttsGeneration
        startupJob?.cancel()
        turns.cancel()
        activeCoordinator?.dispose()
        activeCoordinator = null
        app.speechOutput.stop()
        cancelListenCycle(stopRecognizer = true)
        deadlineJob?.cancel()
        turnInFlight = false
    }

    private fun resumeAfterChallenge() {
        if (isClosing()) return
        if (conversationFailed) { status = SessionStatus.ENDED; return }
        if (!routineActive && !conversationEngine.sessionStarted) {
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
    fun beginChallengeAnswer() { dismissal.beginChallengeAnswer(); challengeWrong = false }

    private fun repeatChallengePrompt() {
        val challenge = activeChallenge ?: return
        if (challenge.showingCode || challengeSolved || ended) return
        stopChallengeVoice()
        val prompt = if (challenge.type == DismissalChallenges.Type.MATH) challenge.mathQuestion?.prompt.orEmpty()
            else routineString(R.string.challenge_memory_prompt)
        val generation = ++ttsGeneration
        app.speechOutput.speak(prompt,
            onStart = { if (generation == ttsGeneration && !disposed && !ended) { everSpoken = true; stopAlarmFallback() } },
            completion = { if (generation == ttsGeneration && !disposed && !ended) startChallengeVoice() }
        )
    }

    fun startChallengeVoice() {
        val challenge = activeChallenge ?: return
        if (isClosing() || challenge.showingCode || challengeSolved) return
        if (!voiceAvailable || alarmId >= 1L && !alarmFallbackStopped.get()) {
            voiceHint = app.getString(R.string.type_fallback_hint)
            return
        }
        stopChallengeVoice()
        partialUserText = ""
        voiceHint = app.getString(R.string.status_listening)
        app.speechInput.startListening(languageTag)
        challengeJob = scope.launch {
            delay(timing.listenTimeoutMs)
            stopChallengeVoice()
            voiceHint = app.getString(R.string.type_fallback_hint)
        }
    }

    private fun stopChallengeVoice() {
        challengeJob?.cancel(); challengeJob = null
        app.speechInput.stopListening()
        microphoneReady = false; microphoneLevel = 0f; speechActive = false; partialUserText = ""
    }

    /**
     * Returns true when the answer unlocked Stop (the session then terminates); a wrong
     * answer regenerates the math question or keeps the code prompt open.
     */
    fun submitChallengeAnswer(typed: String): Boolean {
        if (challengeSolved || activeChallenge == null || disposed) return false
        stopChallengeVoice()
        val correct = dismissal.submitChallengeAnswer(typed)
        challengeWrong = !correct
        challengeSolved = correct
        react(if (correct) com.anaalarm.ui.avatar.AvatarMood.HAPPY else com.anaalarm.ui.avatar.AvatarMood.REASSURING)
        if (correct) challengeJob = scope.launch { delay(timing.challengeSuccessMs); completeChallengeStop() }
        return correct
    }

    /** Called after the challenge-success pose has had a moment on screen. */
    fun completeChallengeStop() { if (!challengeSolved || isClosing()) return; stopChallengeVoice(); dismissal.completeChallengeStop() }

    fun dismissChallenge() { if (challengeSolved || isClosing()) return; stopChallengeVoice(); challengeSolved = false; challengeWrong = false; dismissal.dismissChallenge() }

    /** Display-only access for the memory challenge's visible-code phase. */
    fun revealMemoryCodeForDisplay(): String = dismissal.revealMemoryCodeForDisplay()

    fun snoozeNow() = dismissal.snoozeNow()

    private fun wrapUp() { if (routineActive) requestStop() else dismissal.wrapUp() }

    private fun fail(e: Exception) {
        if (ended) return
        ++turnGeneration
        turns.cancel()
        activeCoordinator?.dispose(); activeCoordinator = null
        turnInFlight = false
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
        if (app.speechOutput.isReady && everSpoken) {
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
        app.speechOutput.speak(
            text = app.getString(R.string.offline_farewell),
            onStart = {
                if (generation == ttsGeneration && !ended) stopAlarmFallback()
            },
            completion = {
                if (generation == ttsGeneration && !ended) {
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
        boundSpeechOutput?.let { if (it.onFocusLost === focusLostCallback) it.onFocusLost = null }
        boundSpeechOutput = null
        cancelNetworkJobs()
        cancelListenCycle(stopRecognizer = true)
        ++ttsGeneration
        app.speechOutput.stop()
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
        app.speechOutput.stop()
        partialUserText = ""
        status = SessionStatus.ENDED
        finalizeConversation()
    }

    private fun cancelListenCycle(stopRecognizer: Boolean) {
        microphoneReady = false; microphoneLevel = 0f; speechActive = false
        listen.cancelListenCycle(stopRecognizer)
    }

    private fun cancelNetworkJobs() {
        routineJob?.cancel(); routineJob = null
        reactionJob?.cancel(); reactionJob = null
        buddyReaction = null
        challengeJob?.cancel(); challengeJob = null
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

    // disposed implies ended: dispose() calls transitionToEnded() first.
    private fun isClosing(): Boolean = ended || ending

    private fun isTimeUp(): Boolean = timing.nowMillis() - sessionStart >= sessionMs
}
