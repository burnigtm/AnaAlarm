package com.anaalarm.ui.wakeup

import android.util.Log
import com.anaalarm.AnaAlarmApp
import com.anaalarm.ai.ApiException
import com.anaalarm.ai.ConversationEngine
import com.anaalarm.ai.stream.StreamingTurnCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * User-turn pipeline: stop-phrase detection, legacy complete-response turns, and streaming turns.
 */
internal class SessionTurnPipeline(
    private val app: AnaAlarmApp,
    private val scope: CoroutineScope,
    private val conversationEngine: ConversationEngine,
    private val host: Host
) {
    interface Host {
        fun isTerminal(): Boolean
        var status: SessionStatus
        var lastUserText: String
        var partialUserText: String
        var voiceHint: String?
        var aiText: String
        var silentStreak: Int
        var turnInFlight: Boolean
        var everSpoken: Boolean
        var activeCoordinator: StreamingTurnCoordinator?
        fun streamingEnabled(): Boolean
        fun cancelListenCycle(stopRecognizer: Boolean)
        fun wrapUp()
        fun speak(text: String)
        fun afterSpeaking()
        fun stopAlarmFallback()
        fun fail(e: Exception)
    }

    private var turnJob: Job? = null

    fun onUserSpeech(text: String) {
        if (host.isTerminal() || host.status != SessionStatus.LISTENING) return
        host.cancelListenCycle(stopRecognizer = true)
        host.lastUserText = text
        host.partialUserText = ""
        host.silentStreak = 0
        host.voiceHint = null
        Log.i(TAG, "Received user speech len=${text.length}")
        if (SessionPhrases.isStopPhrase(text)) {
            host.wrapUp()
            return
        }
        if (host.turnInFlight) return
        host.turnInFlight = true
        host.status = SessionStatus.THINKING
        turnJob = scope.launch {
            try {
                if (host.streamingEnabled()) {
                    runStreamingTurn(text)
                } else {
                    runLegacyTurn(text)
                }
            } catch (e: CancellationException) {
                host.turnInFlight = false
                throw e
            } catch (e: Exception) {
                host.turnInFlight = false
                host.fail(e)
            }
        }
    }

    private suspend fun runLegacyTurn(text: String) {
        val reply = conversationEngine.respond(text)
        host.turnInFlight = false
        if (!host.isTerminal()) host.speak(reply)
    }

    /**
     * Streaming turn: phrases are spoken while the model is still generating. The microphone
     * reopens only after the semantic terminal AND the final utterance completion, which is the
     * point where [StreamingTurnCoordinator.awaitSettled] returns.
     */
    private suspend fun runStreamingTurn(text: String) {
        val coordinator = StreamingTurnCoordinator(
            turnId = StreamingTurnCoordinator.nextTurnId(),
            ttsManager = app.ttsManager,
            onFirstPhraseAudioStarted = {
                host.everSpoken = true
                host.stopAlarmFallback()
            }
        )
        host.activeCoordinator = coordinator
        try {
            val reply = conversationEngine.respondStreaming(text, coordinator::accept)
            coordinator.markTerminalCompleted()
            host.aiText = reply
            host.turnInFlight = false
            coordinator.awaitSettled()
            if (!host.isTerminal()) host.afterSpeaking()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (coordinator.canFallbackToNonStreaming()) {
                // Nothing was audible yet, so a fresh non-streaming request cannot replay speech.
                Log.w(TAG, "Streaming failed before first audio; falling back", e)
                coordinator.dispose()
                val reply = conversationEngine.respond(text, insertUserMessage = false)
                host.turnInFlight = false
                if (!host.isTerminal()) host.speak(reply)
            } else {
                // Some audio already played; an invisible retry would duplicate it.
                coordinator.markTerminalFailed(
                    e as? ApiException ?: ApiException(e.message ?: "ai error")
                )
                host.fail(coordinator.failure ?: e)
            }
        } finally {
            if (host.activeCoordinator === coordinator) host.activeCoordinator = null
        }
    }

    fun cancel() {
        turnJob?.cancel()
        turnJob = null
    }

    companion object {
        private const val TAG = "AnaSession"
    }
}
