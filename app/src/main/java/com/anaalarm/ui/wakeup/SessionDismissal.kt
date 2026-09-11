package com.anaalarm.ui.wakeup

import android.util.Log
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.alarm.AlarmScheduleResult
import com.anaalarm.alarm.AlarmService
import com.anaalarm.alarm.DismissalChallenges
import com.anaalarm.ai.ConversationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stop / snooze / dismissal-challenge / wrap-up / terminate paths for a wake session.
 */
internal class SessionDismissal(
    private val app: AnaAlarmApp,
    private val scope: CoroutineScope,
    private val alarmId: Long,
    private val conversationEngine: ConversationEngine,
    private val host: Host
) {
    interface Host {
        fun isEnded(): Boolean
        fun isEnding(): Boolean
        fun isDisposed(): Boolean
        fun markEnding()
        fun transitionToEnded()
        var status: SessionStatus
        var aiText: String
        var errorText: String?
        var snoozeInFlight: Boolean
        var activeChallenge: StopChallengeUi?
        var everSpoken: Boolean
        fun stopChallengeType(): DismissalChallenges.Type
        fun cancelListenCycle(stopRecognizer: Boolean)
        fun cancelNetworkJobs()
        fun nextTtsGeneration(): Long
        fun currentTtsGeneration(): Long
        fun stopAlarmFallback()
        fun onFinishedOnce()
        fun finalizeConversation()
    }

    private var wrapUpJob: Job? = null
    private var snoozeJob: Job? = null
    private var memoryCode: String? = null

    val snoozeAvailable: Boolean get() = alarmId >= 1L

    fun stopNow() {
        terminateSession(finishActivity = true)
    }

    /**
     * Stop-button entry point: alarms configured with a dismissal challenge must prove the
     * user is awake first. Test sessions (no alarm id) always stop immediately. An already-
     * ended session (e.g. after a cold-start AI failure) must still be dismissible, so the
     * explicit path bypasses the ended guard — the screen would otherwise be stuck open.
     */
    fun requestStop() {
        if (host.isDisposed()) return
        if (host.isEnded() || host.isEnding() ||
            host.stopChallengeType() == DismissalChallenges.Type.NONE
        ) {
            stopNow()
            return
        }
        when (host.stopChallengeType()) {
            DismissalChallenges.Type.MATH -> {
                host.activeChallenge = StopChallengeUi(
                    type = DismissalChallenges.Type.MATH,
                    mathQuestion = DismissalChallenges.mathQuestion()
                )
            }
            DismissalChallenges.Type.MEMORY -> {
                memoryCode = DismissalChallenges.memoryCode()
                host.activeChallenge = StopChallengeUi(
                    type = DismissalChallenges.Type.MEMORY,
                    showingCode = true
                )
            }
            else -> stopNow()
        }
    }

    /** Memory challenge: the user saw the code and is ready to type it back. */
    fun beginChallengeAnswer() {
        val current = host.activeChallenge ?: return
        if (current.type == DismissalChallenges.Type.MEMORY && current.showingCode) {
            host.activeChallenge = current.copy(showingCode = false)
        }
    }

    /**
     * Returns true when the answer unlocked Stop (the session then terminates); a wrong
     * answer regenerates the math question or keeps the code prompt open.
     */
    fun submitChallengeAnswer(typed: String): Boolean {
        val current = host.activeChallenge ?: return true
        val expected: String = when (current.type) {
            DismissalChallenges.Type.MATH ->
                current.mathQuestion?.answer?.toString() ?: return true
            DismissalChallenges.Type.MEMORY -> memoryCode ?: return true
            else -> return true
        }
        if (!DismissalChallenges.isAnswerCorrect(expected, typed)) {
            if (current.type == DismissalChallenges.Type.MATH) {
                host.activeChallenge = current.copy(
                    mathQuestion = DismissalChallenges.mathQuestion()
                )
            }
            return false
        }
        host.activeChallenge = null
        memoryCode = null
        stopNow()
        return true
    }

    fun dismissChallenge() {
        host.activeChallenge = null
        memoryCode = null
    }

    /** Display-only access for the memory challenge's visible-code phase. */
    fun revealMemoryCodeForDisplay(): String = memoryCode.orEmpty()

    fun clearChallengeState() {
        host.activeChallenge = null
        memoryCode = null
    }

    fun snoozeNow() {
        if (!snoozeAvailable || host.snoozeInFlight || host.isEnding() || host.isDisposed()) return
        host.snoozeInFlight = true
        host.errorText = null
        snoozeJob = scope.launch {
            val result = runCatching { app.alarmScheduler.scheduleSnooze(alarmId) }
                .getOrElse {
                    AlarmScheduleResult.Failed(
                        AlarmScheduleResult.Failed.Reason.SYSTEM_ERROR,
                        it
                    )
                }
            host.snoozeInFlight = false
            snoozeJob = null
            if (result is AlarmScheduleResult.Scheduled) {
                terminateSession(finishActivity = true)
            } else if (!host.isDisposed()) {
                // Scheduling failure is recoverable. Keep the existing session (and its alarm
                // fallback) active so the user can retry or explicitly stop it.
                host.errorText = app.getString(
                    if (result is AlarmScheduleResult.Failed &&
                        result.reason == AlarmScheduleResult.Failed.Reason.SNOOZE_LIMIT_REACHED
                    ) {
                        R.string.no_snoozes_left
                    } else {
                        R.string.snooze_failed
                    }
                )
            }
        }
    }

    fun wrapUp() {
        if (host.isEnded() || host.isEnding() || host.isDisposed()) return
        host.markEnding()
        host.cancelListenCycle(stopRecognizer = true)
        host.status = SessionStatus.THINKING
        wrapUpJob = scope.launch {
            try {
                val farewell = conversationEngine.wrapUp()
                if (!host.isEnded() && !host.isDisposed()) speakFarewell(farewell)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!host.isEnded() && !host.isDisposed()) {
                    terminateSession(finishActivity = true)
                }
            }
        }
    }

    private fun speakFarewell(text: String) {
        if (host.isEnded() || host.isDisposed()) return
        host.aiText = text
        host.status = SessionStatus.SPEAKING
        val generation = host.nextTtsGeneration()
        app.ttsManager.speak(
            text = text,
            onStart = {
                if (generation == host.currentTtsGeneration() &&
                    !host.isEnded() && !host.isDisposed()
                ) {
                    host.everSpoken = true
                    host.stopAlarmFallback()
                }
            },
            completion = {
                if (generation == host.currentTtsGeneration() &&
                    !host.isEnded() && !host.isDisposed()
                ) {
                    terminateSession(finishActivity = true)
                }
            }
        )
    }

    fun terminateSession(finishActivity: Boolean) {
        host.transitionToEnded()
        // Explicit Stop/Snooze must silence the service even when the AI session already failed.
        host.stopAlarmFallback()
        if (finishActivity) host.onFinishedOnce()
    }

    fun cancelJobs() {
        wrapUpJob?.cancel()
        wrapUpJob = null
        snoozeJob?.cancel()
        snoozeJob = null
    }

    companion object {
        private const val TAG = "AnaSession"

        fun stopAlarmService(app: AnaAlarmApp, alarmId: Long, gate: AtomicBoolean) {
            if (gate.compareAndSet(false, true)) {
                runCatching { AlarmService.stop(app, alarmId) }
                    .onFailure { Log.w(TAG, "Unable to stop alarm fallback", it) }
            }
        }
    }
}
