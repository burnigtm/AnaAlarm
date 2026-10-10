package com.anaalarm.ui.wakeup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anaalarm.AnaAlarmApp
import com.anaalarm.ai.ConversationEngine
import com.anaalarm.alarm.AlarmScheduleResult
import com.anaalarm.alarm.AlarmScheduler
import com.anaalarm.alarm.DismissalChallenges
import com.anaalarm.voice.SpeechInput
import com.anaalarm.voice.SpeechOutput
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests asynchronous lifecycle boundaries independently of the Activity owner. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SessionLifecycleTest {
    private class Environment {
        val app = mockk<AnaAlarmApp>(relaxed = true)
        val alarms = mockk<AlarmScheduler>(relaxed = true)
        val input = mockk<SpeechInput>(relaxed = true)
        val output = mockk<SpeechOutput>(relaxed = true)
        val engine = mockk<ConversationEngine>(relaxed = true)
        var available = true
        var start: (() -> Unit)? = null
        var complete: (() -> Unit)? = null
        init {
            val context = ApplicationProvider.getApplicationContext<Context>()
            every { app.getString(any()) } answers { context.getString(firstArg()) }
            every { app.alarmScheduler } returns alarms
            every { app.speechInput } returns input
            every { app.speechOutput } returns output
            every { input.isAvailable } answers { available }
            every { output.speak(any(), any(), any()) } answers { start = secondArg(); complete = thirdArg() }
            coEvery { engine.wrapUp() } returns "See you tomorrow"
        }
    }
    private class DismissHost : SessionDismissal.Host {
        var ended = false; var ending = false; var disposed = false
        var challenge = DismissalChallenges.Type.NONE
        var generation = 0L; var finished = 0; var silenced = 0; var resumed = 0; var requested = 0
        override var status = SessionStatus.LISTENING
        override var aiText = ""
        override var errorText: String? = null
        override var snoozeInFlight = false
        override var activeChallenge: StopChallengeUi? = null
        override var everSpoken = false
        override fun isEnded() = ended
        override fun isEnding() = ending
        override fun isDisposed() = disposed
        override fun markEnding() { ending = true }
        override fun transitionToEnded() { ended = true; status = SessionStatus.ENDED }
        override fun pauseForChallenge() = Unit
        override fun resumeAfterSnoozeFailure() { resumed++ }
        override fun resumeAfterChallenge() { resumed++ }
        override fun requestStop() { requested++ }
        override fun stopChallengeType() = challenge
        override fun cancelListenCycle(stopRecognizer: Boolean) = Unit
        override fun cancelNetworkJobs() = Unit
        override fun nextTtsGeneration() = ++generation
        override fun currentTtsGeneration() = generation
        override fun stopAlarmFallback() { silenced++ }
        override fun onFinishedOnce() { finished++ }
        override fun finalizeConversation() = Unit
    }
    @Test fun `snooze is unavailable in preview and cannot schedule during terminal states`() = runTest {
        val e = Environment(); val h = DismissHost()
        val preview = SessionDismissal(e.app, backgroundScope, -1, e.engine, h)
        assertFalse(preview.snoozeAvailable); preview.snoozeNow()
        val d = SessionDismissal(e.app, backgroundScope, 12, e.engine, h)
        h.snoozeInFlight = true; d.snoozeNow(); h.snoozeInFlight = false
        h.ended = true; d.snoozeNow(); h.ended = false
        h.ending = true; d.snoozeNow(); h.ending = false
        h.disposed = true; d.snoozeNow(); d.requestStop(); d.wrapUp()
        runCurrent(); coVerify(exactly = 0) { e.alarms.scheduleSnooze(any<Long>()) }
    }
    @Test fun `scheduler exceptions are recoverable and disposed screens receive no error`() = runTest {
        val e = Environment(); val h = DismissHost()
        val d = SessionDismissal(e.app, backgroundScope, 12, e.engine, h)
        coEvery { e.alarms.scheduleSnooze(12L) } throws IllegalStateException("alarm manager")
        d.snoozeNow(); runCurrent()
        assertNotNull(h.errorText); assertEquals(1, h.resumed); assertFalse(h.snoozeInFlight)
        val result = CompletableDeferred<AlarmScheduleResult>()
        coEvery { e.alarms.scheduleSnooze(12L) } coAnswers { result.await() }
        h.errorText = null; d.snoozeNow(); runCurrent(); h.disposed = true
        result.complete(AlarmScheduleResult.Failed(AlarmScheduleResult.Failed.Reason.SYSTEM_ERROR)); runCurrent()
        assertNull(h.errorText); assertEquals(1, h.resumed)
        d.cancelJobs(); d.cancelJobs()
    }
    @Test fun `failed wrap up dismisses once and cancellation cannot produce farewell audio`() = runTest {
        val e = Environment(); val h = DismissHost()
        val d = SessionDismissal(e.app, backgroundScope, 12, e.engine, h)
        coEvery { e.engine.wrapUp() } throws IllegalStateException("network")
        d.wrapUp(); runCurrent(); assertEquals(1, h.finished); assertTrue(h.ended)
        d.wrapUp(); assertEquals(1, h.finished)
        val h2 = DismissHost(); val d2 = SessionDismissal(e.app, backgroundScope, 12, e.engine, h2)
        coEvery { e.engine.wrapUp() } coAnswers { awaitCancellation() }
        d2.wrapUp(); runCurrent(); d2.cancelJobs(); runCurrent()
        verify(exactly = 0) { e.output.speak(any(), any(), any()) }
    }
    @Test fun `disposed or ended owner ignores delayed farewell and errors`() = runTest {
        for (disposed in listOf(false, true)) for (failure in listOf(false, true)) {
            val e = Environment(); val h = DismissHost(); val result = CompletableDeferred<String>()
            val d = SessionDismissal(e.app, backgroundScope, 12, e.engine, h)
            coEvery { e.engine.wrapUp() } coAnswers { result.await() }
            d.wrapUp(); runCurrent()
            if (disposed) h.disposed = true else h.ended = true
            if (failure) result.completeExceptionally(IllegalStateException("network")) else result.complete("late")
            runCurrent(); assertEquals(0, h.finished)
            verify(exactly = 0) { e.output.speak(any(), any(), any()) }
        }
    }
    @Test fun `farewell callbacks are generation scoped and respect disposal and terminal state`() = runTest {
        for (state in 0..3) {
            val e = Environment(); val h = DismissHost()
            val d = SessionDismissal(e.app, backgroundScope, 12, e.engine, h)
            d.wrapUp(); runCurrent(); assertEquals(SessionStatus.SPEAKING, h.status)
            when (state) { 1 -> h.generation++; 2 -> h.ended = true; 3 -> h.disposed = true }
            e.start!!.invoke(); e.complete!!.invoke()
            assertEquals(if (state == 0) 1 else 0, h.finished)
            assertEquals(state == 0, h.everSpoken)
        }
    }
    @Test fun `memory validation keeps wrong codes and explicit challenge cancellation resumes`() = runTest {
        val e = Environment(); val h = DismissHost(); h.challenge = DismissalChallenges.Type.MEMORY
        val d = SessionDismissal(e.app, backgroundScope, 12, e.engine, h)
        d.requestStop(); d.requestStop(); val code = d.revealMemoryCodeForDisplay()
        d.beginChallengeAnswer(); d.beginChallengeAnswer()
        assertFalse(d.submitChallengeAnswer("xxxxx")); assertEquals(code, d.revealMemoryCodeForDisplay())
        assertTrue(d.submitChallengeAnswer(code)); d.dismissChallenge()
        assertNull(h.activeChallenge); assertEquals(1, h.resumed)
        d.beginChallengeAnswer(); assertTrue(d.submitChallengeAnswer("ignored"))
        h.ending = true; d.requestStop(); assertTrue(h.ended)
    }
    @Test fun `deadline preserves challenge policy and optional finalization leaves screen open`() = runTest {
        val e = Environment(); val h = DismissHost(); h.challenge = DismissalChallenges.Type.MATH
        val d = SessionDismissal(e.app, backgroundScope, 12, e.engine, h)
        d.wrapUp(); assertEquals(1, h.requested)
        h.ending = true; d.wrapUp(); assertEquals(1, h.requested)
        d.terminateSession(finishActivity = false)
        assertEquals(0, h.finished); assertTrue(h.ended)
    }

    private class ListenHost : SessionListenCycle.Host {
        var terminal = false; var typing = false; var timeUp = false; var current = true
        var generation = 0L; var wrapped = 0; var fallback = 0; var reassurance = 0; var spoken = 0
        override var status = SessionStatus.LISTENING
        override var partialUserText = ""
        override var voiceHint: String? = null
        override var silentStreak = 0
        override fun isTerminal() = terminal
        override fun isTextInputActive() = typing
        override fun voiceGeneration() = generation
        override fun nextVoiceGeneration() = ++generation
        override fun isCurrentVoiceGeneration(generation: Long) = current && generation == this.generation
        override fun wrapUp() { wrapped++ }
        override fun switchToTextInput(speakPrompt: Boolean) { typing = true; fallback++ }
        override fun speak(text: String) { spoken++ }
        override fun reassure() { reassurance++ }
        override fun languageTag() = "en-US"
        override fun isTimeUp() = timeUp
    }
    @Test fun `settle respects deadline generation and explicit typing choice`() = runTest {
        val e = Environment(); val h = ListenHost()
        val l = SessionListenCycle(e.app, backgroundScope, h, SessionTiming(microphoneSettleMs = 10))
        h.terminal = true; l.scheduleAfterSpeaking(); h.terminal = false
        h.timeUp = true; l.scheduleAfterSpeaking(); assertEquals(1, h.wrapped); h.timeUp = false
        l.scheduleAfterSpeaking(); h.current = false; advanceTimeBy(11); runCurrent()
        verify(exactly = 0) { e.input.startListening(any()) }
        h.current = true; l.scheduleAfterSpeaking(); h.typing = true; advanceTimeBy(11); runCurrent()
        assertNotNull(h.voiceHint); verify(exactly = 0) { e.input.startListening(any()) }
        l.startListeningAttempt(); h.typing = false; h.terminal = true; l.startListeningAttempt()
        h.terminal = false; e.available = false; l.startListeningAttempt(); assertEquals(1, h.fallback)
        l.cancelListenCycle(false); verify(exactly = 0) { e.input.stopListening() }
    }
    @Test fun `pending timeout respects newer recognition typing and elapsed session`() = runTest {
        for (state in 0..2) {
            val e = Environment(); val h = ListenHost()
            val l = SessionListenCycle(e.app, backgroundScope, h, SessionTiming(listenTimeoutMs = 10))
            l.startListeningAttempt()
            when (state) { 0 -> h.current = false; 1 -> h.typing = true; 2 -> h.timeUp = true }
            advanceTimeBy(11); runCurrent()
            assertEquals(if (state == 2) 1 else 0, h.wrapped)
            assertEquals(if (state == 2) 1 else 0, h.reassurance)
        }
    }
    @Test fun `late errors and partials cannot alter a speaking or closed session`() = runTest {
        val e = Environment(); val h = ListenHost()
        val l = SessionListenCycle(e.app, backgroundScope, h)
        h.terminal = true; l.onPartialSpeech("late"); l.onSpeechError(8)
        h.terminal = false; h.status = SessionStatus.SPEAKING
        l.onPartialSpeech("late"); l.onSpeechError(8)
        assertEquals("", h.partialUserText); assertEquals(0, h.reassurance)
        h.status = SessionStatus.LISTENING; l.onSpeechError(16)
        h.typing = true; advanceTimeBy(351); runCurrent()
        verify(exactly = 0) { e.input.startListening(any()) }
        h.typing = false; l.onSpeechError(8); assertEquals(1, h.fallback)
    }
    @Test fun `retry captured before generation change cannot start another recognition`() = runTest {
        val e = Environment(); val h = ListenHost()
        val l = SessionListenCycle(e.app, backgroundScope, h)
        l.onSpeechError(8); h.current = false; advanceTimeBy(351); runCurrent()
        verify(exactly = 0) { e.input.startListening(any()) }
        l.cancelListenCycle(true)
    }
}
