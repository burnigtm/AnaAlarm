package com.anaalarm.ui.wakeup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anaalarm.AnaAlarmApp
import com.anaalarm.ai.ConversationEngine
import com.anaalarm.data.AlarmEntity
import com.anaalarm.data.AppSettings
import com.anaalarm.data.HabitEventEntity
import com.anaalarm.data.SessionModes
import com.anaalarm.ui.avatar.AvatarMood
import com.anaalarm.voice.SpeechInput
import com.anaalarm.voice.SpeechOutput
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VoiceSessionTest {
    private class Input : SpeechInput {
        override var isAvailable = true
        override var isListening = false
        override var onResult: ((String) -> Unit)? = null
        override var onPartialResult: ((String) -> Unit)? = null
        override var onError: ((Int) -> Unit)? = null
        override var onReady: (() -> Unit)? = null
        override var onSpeechActivity: ((Boolean) -> Unit)? = null
        override var onLevel: ((Float) -> Unit)? = null
        var starts = 0
        override fun startListening(languageTag: String) { isListening = true; starts++; onReady?.invoke() }
        override fun stopListening() { isListening = false }
        override fun destroy() = stopListening()
        fun say(text: String) { isListening = false; onResult?.invoke(text) }
    }
    private class Fixture(scope: CoroutineScope, scheduler: TestCoroutineScheduler,
                          settings: AppSettings = AppSettings(), challenge: Int = 0, done: List<HabitEventEntity> = emptyList(), alarmId: Long = 12L,
                          today: () -> java.time.LocalDate = { java.time.LocalDate.parse("2026-10-10") }) {
        val app = mockk<AnaAlarmApp>(relaxed = true)
        val alarms = mockk<com.anaalarm.alarm.AlarmScheduler>(relaxed = true)
        val memory = mockk<com.anaalarm.data.MemoryStore>(relaxed = true)
        val engine = mockk<ConversationEngine>(relaxed = true)
        val speaker = mockk<SpeechOutput>(relaxed = true)
        val input = Input()
        val speech = mutableListOf<String>()
        val starts = mutableListOf<() -> Unit>()
        val completions = mutableListOf<() -> Unit>()
        var focusLost: (() -> Unit)? = null
        val queued = mutableListOf<Triple<String, () -> Unit, () -> Unit>>()
        var finishes = 0
        val controller: SessionController
        init {
            val context = ApplicationProvider.getApplicationContext<Context>()
            every { app.resources } returns context.resources
            every { app.createConfigurationContext(any()) } answers { context.createConfigurationContext(firstArg()) }
            every { app.getString(any()) } answers { context.getString(firstArg()) }
            every { app.alarmScheduler } returns alarms
            every { alarms.remainingSnoozes(any()) } returns null
            every { app.memoryStore } returns memory
            every { app.settingsStore.settings } returns flowOf(settings)
            every { app.newConversationEngine() } returns engine
            every { engine.sessionStarted } returns true
            every { app.speechInput } returns input
            every { app.speechOutput } returns speaker
            every { app.applicationScope } returns scope
            every { speaker.onFocusLost = any() } answers { focusLost = firstArg() }
            every { speaker.isReady } returns true
            coEvery { speaker.awaitReady(any()) } returns true
            coEvery { memory.getAlarm(12L) } returns AlarmEntity(id = 12, hour = 7, minute = 0, challengeType = challenge)
            coEvery { memory.habitsForDate(any()) } returns done
            coEvery { engine.startSession(any()) } returns "Good morning"
            coEvery { engine.respond(any(), any()) } returns "Reply"
            every { speaker.speak(any(), any(), any()) } answers {
                speech += firstArg<String>(); starts += secondArg<() -> Unit>(); completions += thirdArg<() -> Unit>()
            }
            every { speaker.speakQueued(any(), any(), any(), any(), any(), any()) } answers {
                queued += Triple(firstArg<String>(), arg<() -> Unit>(4), arg<() -> Unit>(5))
            }
            controller = SessionController(app, scope, { finishes++ }, true, alarmId,
                SessionTiming(today = today, nowMillis = { scheduler.currentTime }, microphoneSettleMs = 10, listenTimeoutMs = 1000,
                    retryMs = 100, reactionMs = 650, challengeSuccessMs = 750))
        }
        fun play() { starts.last().invoke(); completions.last().invoke() }
    }

    private fun TestScope.ready(f: Fixture) { f.controller.start(); runCurrent(); f.play(); advanceTimeBy(11); runCurrent() }
    @Test fun `partial results and speech activity do not submit model turns`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f)
        f.input.onPartialResult?.invoke("partial"); f.input.onSpeechActivity?.invoke(true); f.input.onLevel?.invoke(0.6f)
        assertEquals("partial", f.controller.partialUserText)
        assertEquals(AvatarMood.ACKNOWLEDGING, f.controller.buddyReaction)
        assertEquals(0.6f, f.controller.microphoneLevel, 0f)
        assertTrue(f.controller.microphoneReady)
        coVerify(exactly = 0) { f.engine.respond(any(), any()) }
        f.input.say("final"); runCurrent()
        assertEquals("final", f.controller.lastUserText)
        assertEquals("", f.controller.partialUserText)
        assertEquals(0f, f.controller.microphoneLevel, 0f)
        coVerify(exactly = 1) { f.engine.respond("final", any()) }
    }
    @Test fun `duplicate final results cannot submit during thinking`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f); f.input.say("hello"); f.input.say("duplicate"); runCurrent()
        coVerify(exactly = 1) { f.engine.respond(any(), any()) }
    }
    @Test fun `repeated taps restart one reaction timer`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.controller.reactToTap(); advanceTimeBy(500)
        f.controller.reactToTap(); advanceTimeBy(151); runCurrent()
        assertEquals(AvatarMood.HAPPY, f.controller.buddyReaction)
        advanceTimeBy(500); runCurrent()
        assertNull(f.controller.buddyReaction)
    }
    @Test fun `disposal cancels reaction and detaches all speech callbacks`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f); f.controller.reactToTap(); f.controller.dispose(); advanceTimeBy(1000); runCurrent()
        assertNull(f.controller.buddyReaction); assertNull(f.input.onResult); assertNull(f.input.onLevel)
        assertNull(f.input.onSpeechActivity); assertNull(f.input.onReady)
        coVerify(exactly = 1) { f.engine.endSession() }
    }
    @Test fun `routine is offline and completed habits are persisted before advancing`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE, habits = listOf("Water", "Walk")))
        ready(f)
        assertEquals("Water", f.controller.routineHabit)
        f.input.say("done"); runCurrent()
        coVerify(exactly = 1) { f.memory.setHabitDone("Water", any(), true) }
        assertEquals("Walk", f.controller.routineHabit)
        coVerify(exactly = 0) { f.engine.startSession(any()) }
        coVerify(exactly = 0) { f.engine.respond(any(), any()) }
    }
    @Test fun `routine skips without marking and saves priority without a model request`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE, habits = listOf("Water")))
        ready(f); f.input.say("skip"); runCurrent(); f.play(); advanceTimeBy(11); runCurrent()
        assertNull(f.controller.routineHabit)
        f.input.say("Finish my project"); runCurrent()
        coVerify(exactly = 0) { f.memory.setHabitDone(any(), any(), any()) }
        coVerify(exactly = 1) { f.memory.savePriority("Finish my project", any()) }
        assertTrue(f.controller.routineFinished)
        assertEquals(0, f.finishes)
    }
    @Test fun `routine storage failure retains current habit and offers typing`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE, habits = listOf("Water")))
        coEvery { f.memory.setHabitDone(any(), any(), any()) } throws IllegalStateException("disk")
        ready(f); assertSame(f.memory, f.app.memoryStore); f.input.say("done"); runCurrent()
        assertEquals("Water", f.controller.routineHabit)
        assertTrue(f.controller.textInputActive)
        assertEquals(SessionStatus.LISTENING, f.controller.status)
        assertEquals(AvatarMood.REASSURING, f.controller.buddyReaction)
    }
    @Test fun `stopped routine cannot resume after a suspended save`() = runTest {
        val saved = CompletableDeferred<Unit>()
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE, habits = listOf("Water")))
        coEvery { f.memory.setHabitDone(any(), any(), any()) } coAnswers { saved.await() }
        ready(f); assertSame(f.memory, f.app.memoryStore); f.input.say("done"); runCurrent(); f.controller.stopNow(); saved.complete(Unit); runCurrent()
        assertEquals(SessionStatus.ENDED, f.controller.status)
        assertEquals(1, f.finishes)
    }
    @Test fun `both languages run localized offline prompts and already done habits are omitted`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE, language = "pt", habits = listOf("Water", "Walk")),
            done = listOf(HabitEventEntity(name = "Water", date = "2026-10-10", done = true)))
        ready(f)
        assertEquals("Walk", f.controller.routineHabit)
        assertTrue(f.speech.single().contains("Diga feito"))
    }
    @Test fun `spoken stop enforces challenge and correct answer finishes exactly once`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, challenge = 1)
        ready(f); f.input.say("stop")
        val answer = f.controller.activeChallenge!!.mathQuestion!!.answer.toString()
        assertEquals(0, f.finishes)
        f.controller.startChallengeVoice(); f.input.say(answer)
        assertTrue(f.controller.challengeSolved)
        f.controller.requestStop(); assertTrue(f.controller.challengeSolved)
        f.controller.dismissChallenge(); assertNotNull(f.controller.activeChallenge)
        advanceTimeBy(751); runCurrent()
        assertEquals(1, f.finishes); assertEquals(SessionStatus.ENDED, f.controller.status)
        f.controller.completeChallengeStop(); assertEquals(1, f.finishes)
    }
    @Test fun `ambiguous challenge input does not regenerate or unlock the challenge`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, challenge = 1)
        ready(f); f.input.say("stop")
        val question = f.controller.activeChallenge!!.mathQuestion
        f.controller.startChallengeVoice(); f.input.say("two or three")
        assertEquals(question, f.controller.activeChallenge!!.mathQuestion)
        assertTrue(f.controller.challengeWrong); assertFalse(f.controller.challengeSolved)
        assertEquals(0, f.finishes)
    }
    @Test fun `memory challenge does not listen while the code is visible`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, challenge = 2)
        ready(f); f.input.say("stop"); val count = f.input.starts
        f.controller.startChallengeVoice(); assertEquals(count, f.input.starts)
        f.controller.beginChallengeAnswer(); f.controller.startChallengeVoice()
        val code = f.controller.revealMemoryCodeForDisplay()
        f.input.say(code.toCharArray().joinToString(" "))
        assertTrue(f.controller.challengeSolved)
    }
    @Test fun `voice challenge timeout and error preserve typing and correctness policy`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, challenge = 1)
        ready(f); f.input.say("stop"); f.controller.startChallengeVoice()
        advanceTimeBy(1001); runCurrent()
        assertFalse(f.input.isListening); assertNotNull(f.controller.activeChallenge)
        f.controller.startChallengeVoice(); f.input.onError?.invoke(3)
        assertNotNull(f.controller.activeChallenge); assertFalse(f.controller.challengeSolved)
    }
    @Test fun `local repeat and type commands avoid the model`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f); f.input.say("repeat"); assertEquals(2, f.speech.size)
        f.play(); advanceTimeBy(11); runCurrent(); f.input.say("digitar")
        assertTrue(f.controller.textInputActive)
        coVerify(exactly = 0) { f.engine.respond(any(), any()) }
    }
    @Test fun `silence retries then falls back to typing`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f); f.input.onError?.invoke(7); advanceTimeBy(101); runCurrent()
        assertEquals(2, f.input.starts)
        f.input.onError?.invoke(6)
        assertTrue(f.controller.textInputActive)
    }
    @Test fun `hard deadline in routine opens configured challenge`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE, sessionMinutes = 5), challenge = 1)
        ready(f); advanceTimeBy(390001); runCurrent()
        assertNotNull(f.controller.activeChallenge)
        assertEquals(0, f.finishes)
    }
    @Test fun `cancelled challenge resumes offline routine without starting the model`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE), challenge = 1)
        ready(f); f.input.say("stop"); f.controller.startChallengeVoice(); f.input.say("cancel")
        assertNull(f.controller.activeChallenge)
        coVerify(exactly = 0) { f.engine.startSession(any()) }
    }
    @Test fun `startup errors expose actionable messages and preserve dismissal`() = runTest {
        for (message in listOf("no api key", "invalid api key", "ssl error", "network error", "unexpected")) {
            val f = Fixture(backgroundScope, testScheduler)
            coEvery { f.engine.startSession(any()) } throws com.anaalarm.ai.ApiException(message)
            f.controller.start(); runCurrent()
            assertTrue(f.controller.conversationFailed)
            assertFalse(f.controller.retryAvailable && message in setOf("no api key", "invalid api key"))
            assertNotNull(f.controller.errorText)
            f.controller.requestStop(); runCurrent(); assertEquals(1, f.finishes)
            f.controller.dispose(); runCurrent()
            coVerify(exactly = 1) { f.engine.endSession() }
        }
    }
    @Test fun `transient startup retry starts a fresh greeting and failure without speech does not silence alarm`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        coEvery { f.engine.startSession(any()) } throws com.anaalarm.ai.ApiException("network error") andThen "Welcome back"
        f.controller.start(); runCurrent()
        assertEquals(SessionStatus.ENDED, f.controller.status)
        assertTrue(f.controller.retryAvailable)
        f.controller.retry(); runCurrent()
        assertFalse(f.controller.conversationFailed)
        assertEquals("Welcome back", f.controller.aiText)
        f.play(); advanceTimeBy(11); runCurrent()
        assertEquals(SessionStatus.LISTENING, f.controller.status)
    }
    @Test fun `failed conversational turn speaks fallback and retry resubmits accepted input once`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        coEvery { f.engine.respond(any(), any()) } throws com.anaalarm.ai.ApiException("network error") andThen "Recovered"
        ready(f); f.input.say("My plans"); runCurrent(); f.play()
        assertEquals(SessionStatus.ENDED, f.controller.status)
        f.controller.retry(); runCurrent()
        assertEquals("Recovered", f.controller.aiText)
        coVerify(exactly = 2) { f.engine.respond("My plans", any()) }
    }
    @Test fun `stop cancels pending model response and late speech cannot revive a session`() = runTest {
        val response = CompletableDeferred<String>()
        val f = Fixture(backgroundScope, testScheduler)
        coEvery { f.engine.respond(any(), any()) } coAnswers { response.await() }
        ready(f); val latePartial = f.input.onPartialResult!!; val lateFinal = f.input.onResult!!
        f.input.say("hello"); runCurrent(); f.controller.stopNow(); response.complete("too late")
        latePartial("late"); lateFinal("late"); runCurrent()
        assertEquals(SessionStatus.ENDED, f.controller.status)
        assertEquals(1, f.finishes); assertEquals("", f.controller.partialUserText)
        coVerify(exactly = 1) { f.engine.endSession() }
    }
    @Test fun `no speech callback triggers timeout and then typing after a second silent cycle`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f); advanceTimeBy(1001); runCurrent()
        assertEquals(AvatarMood.REASSURING, f.controller.buddyReaction)
        f.play(); advanceTimeBy(11); runCurrent(); advanceTimeBy(1001); runCurrent()
        assertTrue(f.controller.textInputActive)
    }
    @Test fun `all hard recognition errors fall back without submitting to the model`() = runTest {
        for (code in listOf(1, 2, 3, 4, 5, 9, 10, 11, 12, 13, 14, 15)) {
            val f = Fixture(backgroundScope, testScheduler)
            ready(f); f.input.onPartialResult!!.invoke("draft"); f.input.onError!!.invoke(code)
            assertTrue(f.controller.textInputActive); assertEquals("", f.controller.partialUserText)
            coVerify(exactly = 0) { f.engine.respond(any(), any()) }
            f.controller.dispose()
        }
    }
    @Test fun `blank and busy typed submissions retain draft and input switches cancel recognition`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.controller.typedDraft = "Keep this"
        assertFalse(f.controller.submitText("no"))
        ready(f); assertFalse(f.controller.submitText("  "))
        f.controller.setTextInput(true)
        assertFalse(f.input.isListening); assertTrue(f.controller.textInputActive)
        assertTrue(f.controller.submitText(" hello ")); runCurrent()
        assertFalse(f.controller.submitText("duplicate"))
        assertEquals("Keep this", f.controller.typedDraft)
        assertEquals("hello", f.controller.lastUserText)
    }
    @Test fun `wrong typed challenge answer stays open and cancellation preserves conversation`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, challenge = 1)
        ready(f); f.controller.requestStop()
        assertFalse(f.controller.submitChallengeAnswer("-100"))
        assertTrue(f.controller.challengeWrong)
        f.controller.dismissChallenge()
        assertNull(f.controller.activeChallenge); assertFalse(f.controller.challengeWrong)
        assertEquals(SessionStatus.LISTENING, f.controller.status)
    }
    @Test fun `challenge microphone stays closed before any audible TTS`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, challenge = 1)
        f.controller.start(); runCurrent(); f.controller.requestStop(); f.controller.startChallengeVoice()
        assertEquals(0, f.input.starts)
        assertNotNull(f.controller.activeChallenge)
    }
    @Test fun `TTS unavailable at startup gives a usable offline routine in typing mode`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE))
        coEvery { f.speaker.awaitReady(any()) } returns false
        every { f.speaker.isReady } returns false
        f.controller.start(); runCurrent()
        assertEquals(SessionStatus.LISTENING, f.controller.status)
        assertTrue(f.controller.textInputActive); assertEquals(0, f.input.starts)
        assertTrue(f.controller.aiText.isNotBlank())
        f.controller.submitText("My priority"); runCurrent()
        assertTrue(f.controller.routineFinished)
    }
    @Test fun `routine priority save failure can be retried without finishing`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE))
        coEvery { f.memory.savePriority(any(), any()) } throws IllegalStateException("disk") andThen Unit
        ready(f); f.input.say("A plan"); runCurrent()
        assertFalse(f.controller.routineFinished); assertTrue(f.controller.textInputActive)
        f.controller.submitText("A plan"); runCurrent()
        assertTrue(f.controller.routineFinished)
        assertNull(f.controller.errorText)
    }
    @Test fun `routine rejects accidental conversational answers to a habit without advancing`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE, habits = listOf("Water")))
        ready(f); f.input.say("maybe later"); runCurrent()
        assertEquals("Water", f.controller.routineHabit)
        coVerify(exactly = 0) { f.memory.setHabitDone(any(), any(), any()) }
        coVerify(exactly = 0) { f.engine.respond(any(), any()) }
    }
    @Test fun `local snooze routes scheduling failure back to active conversation`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        coEvery { f.alarms.scheduleSnooze(12L) } returns com.anaalarm.alarm.AlarmScheduleResult.Failed(
            com.anaalarm.alarm.AlarmScheduleResult.Failed.Reason.SNOOZE_LIMIT_REACHED)
        ready(f); f.input.say("snooze"); runCurrent(); advanceTimeBy(11); runCurrent()
        assertEquals(0, f.finishes); assertNotNull(f.controller.errorText)
        assertEquals(SessionStatus.LISTENING, f.controller.status)
        coVerify(exactly = 0) { f.engine.respond(any(), any()) }
    }
    @Test fun `permission loss disables voice and granting it allows explicit recovery`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f)
        every { f.app.checkPermission(any(), any(), any()) } returns android.content.pm.PackageManager.PERMISSION_DENIED
        every { f.app.checkSelfPermission(any()) } returns android.content.pm.PackageManager.PERMISSION_DENIED
        f.controller.refreshVoiceAvailability()
        assertFalse(f.controller.voiceAvailable); assertTrue(f.controller.textInputActive); assertFalse(f.input.isListening)
        f.controller.setTextInput(false); assertTrue(f.controller.textInputActive)
        every { f.app.checkPermission(any(), any(), any()) } returns android.content.pm.PackageManager.PERMISSION_GRANTED
        every { f.app.checkSelfPermission(any()) } returns android.content.pm.PackageManager.PERMISSION_GRANTED
        f.controller.refreshVoiceAvailability(); f.controller.setTextInput(false)
        assertTrue(f.controller.voiceAvailable); assertFalse(f.controller.textInputActive); assertTrue(f.input.isListening)
        f.controller.enableTextInput(); assertTrue(f.controller.textInputActive)
    }
    @Test fun `missing recognizer offers typing and signal callbacks are bounded and ignored after stop`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f)
        val level = f.input.onLevel!!; val speech = f.input.onSpeechActivity!!; val ready = f.input.onReady!!
        level(Float.NaN); assertEquals(0f, f.controller.microphoneLevel, 0f)
        level(100f); assertEquals(1f, f.controller.microphoneLevel, 0f)
        speech(false); assertFalse(f.controller.speechActive)
        f.input.isAvailable = false; f.controller.refreshVoiceAvailability()
        assertFalse(f.controller.voiceAvailable); assertTrue(f.controller.textInputActive)
        f.controller.stopNow(); level(0.5f); speech(true); ready()
        assertEquals(0f, f.controller.microphoneLevel, 0f); assertFalse(f.controller.speechActive)
        assertFalse(f.controller.microphoneReady)
    }
    @Test fun `challenge repeat and typing commands are local and preserve validation`() = runTest {
        for (type in listOf(1, 2)) {
            val f = Fixture(backgroundScope, testScheduler, challenge = type)
            ready(f); f.controller.requestStop()
            assertFalse(f.controller.canRepeat); assertFalse(f.controller.canInterrupt)
            if (type == 2) f.controller.beginChallengeAnswer()
            f.controller.startChallengeVoice(); f.input.onPartialResult!!.invoke("partial answer")
            assertEquals("partial answer", f.controller.partialUserText)
            f.input.say("repeat"); assertTrue(f.speech.size > 1)
            f.play(); assertTrue(f.input.isListening)
            f.input.say("type"); assertFalse(f.input.isListening)
            assertNotNull(f.controller.activeChallenge)
            assertFalse(f.controller.submitText("not a challenge answer"))
            coVerify(exactly = 0) { f.engine.respond(any(), any()) }
            f.controller.dismissChallenge()
        }
    }
    @Test fun `ending one session does not erase callbacks owned by a later client`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f)
        val final: (String) -> Unit = {}; val partial: (String) -> Unit = {}; val error: (Int) -> Unit = {}
        val ready: () -> Unit = {}; val activity: (Boolean) -> Unit = {}; val level: (Float) -> Unit = {}
        f.input.onResult = final; f.input.onPartialResult = partial; f.input.onError = error
        f.input.onReady = ready; f.input.onSpeechActivity = activity; f.input.onLevel = level
        f.controller.dispose(keepAlarmFallback = true)
        assertSame(final, f.input.onResult); assertSame(partial, f.input.onPartialResult); assertSame(error, f.input.onError)
        assertSame(ready, f.input.onReady); assertSame(activity, f.input.onSpeechActivity); assertSame(level, f.input.onLevel)
        f.controller.dispose()
    }
    @Test fun `conversation ignores routine controls and a finished routine cannot mark another habit`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f); f.controller.advanceRoutine(true); f.controller.startChallengeVoice(); f.controller.beginChallengeAnswer()
        assertFalse(f.controller.submitChallengeAnswer("1"))
        coVerify(exactly = 0) { f.memory.setHabitDone(any(), any(), any()) }
        val routine = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE))
        ready(routine); routine.input.say("My priority"); runCurrent(); routine.controller.advanceRoutine(true)
        assertTrue(routine.controller.routineFinished)
        coVerify(exactly = 0) { routine.memory.setHabitDone(any(), any(), any()) }
    }
    @Test fun `hard deadline closes a pending model turn and finishes exactly once`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMinutes = 5))
        val response = CompletableDeferred<String>()
        coEvery { f.engine.respond(any(), any()) } coAnswers { response.await() }
        coEvery { f.engine.wrapUp() } returns "Goodbye"
        ready(f); f.input.say("Hello"); runCurrent()
        advanceTimeBy(390001); runCurrent()
        assertEquals("Goodbye", f.speech.last())
        assertFalse(f.controller.canInterrupt); assertFalse(f.controller.submitText("late"))
        f.play(); runCurrent(); response.complete("too late"); runCurrent()
        assertEquals(SessionStatus.ENDED, f.controller.status); assertEquals(1, f.finishes)
        coVerify(exactly = 1) { f.engine.endSession() }
    }
    @Test fun `streaming settles only after the final phrase playback`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(streamingEnabled = true))
        coEvery { f.engine.respondStreaming(any(), any()) } coAnswers {
            val delta = secondArg<suspend (String) -> Unit>()
            delta("Here is your morning plan."); delta(" Have a wonderful and productive day.")
            "Here is your morning plan. Have a wonderful and productive day."
        }
        ready(f); f.input.say("My plan"); runCurrent()
        assertEquals(2, f.queued.size); assertFalse(f.input.isListening)
        f.queued[0].second(); f.queued[0].third(); runCurrent()
        assertEquals(SessionStatus.SPEAKING, f.controller.status); assertFalse(f.input.isListening)
        f.queued[1].third(); runCurrent(); advanceTimeBy(11); runCurrent()
        assertEquals(SessionStatus.LISTENING, f.controller.status); assertTrue(f.input.isListening)
    }
    @Test fun `stream failure before playback falls back without reinserting accepted speech`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(streamingEnabled = true))
        coEvery { f.engine.respondStreaming(any(), any()) } coAnswers {
            secondArg<suspend (String) -> Unit>()("Here is your morning plan.")
            throw com.anaalarm.ai.ApiException("network error")
        }
        ready(f); f.input.say("My plan"); runCurrent()
        coVerify(exactly = 1) { f.engine.respond("My plan", false) }
        assertFalse(f.controller.conversationFailed)
        assertEquals("Reply", f.controller.aiText)
        f.queued.first().second(); f.queued.first().third(); runCurrent()
        assertEquals(SessionStatus.THINKING, f.controller.status)
    }
    @Test fun `stream failure after playback surfaces error without an invisible retry`() = runTest {
        for (failure in listOf(com.anaalarm.ai.ApiException("network error"), IllegalStateException("broken stream"))) {
            val f = Fixture(backgroundScope, testScheduler, AppSettings(streamingEnabled = true))
            coEvery { f.engine.respondStreaming(any(), any()) } coAnswers {
                secondArg<suspend (String) -> Unit>()("Here is your morning plan.")
                f.queued.last().second()
                throw failure
            }
            ready(f); f.input.say("My plan"); runCurrent()
            assertTrue(f.controller.conversationFailed)
            coVerify(exactly = 0) { f.engine.respond(any(), any()) }
            f.controller.dispose()
        }
    }
    @Test fun `interrupting a stream cancels pending audio and drops late deltas`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(streamingEnabled = true))
        var late: (suspend (String) -> Unit)? = null
        val complete = CompletableDeferred<String>()
        coEvery { f.engine.respondStreaming(any(), any()) } coAnswers {
            late = secondArg<suspend (String) -> Unit>(); late!!.invoke("Here is your morning plan."); complete.await()
        }
        ready(f); f.input.say("My plan"); runCurrent()
        val count = f.queued.size; f.controller.interrupt(); runCurrent()
        late!!.invoke("stale text"); complete.complete("stale reply"); runCurrent()
        assertEquals(count, f.queued.size)
        assertEquals(SessionStatus.LISTENING, f.controller.status)
        assertFalse(f.controller.conversationFailed)
    }
    @Test fun `streaming setting with unavailable TTS still delivers usable typed replies`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(streamingEnabled = true))
        coEvery { f.speaker.awaitReady(any()) } returns false
        every { f.speaker.isReady } returns false
        f.controller.start(); runCurrent()
        assertTrue(f.controller.textInputActive)
        assertTrue(f.controller.submitText("Hello")); runCurrent()
        assertEquals("Reply", f.controller.aiText)
        f.completions.last().invoke(); runCurrent()
        assertEquals(SessionStatus.LISTENING, f.controller.status)
        coVerify(exactly = 1) { f.engine.respond("Hello", any()) }
        coVerify(exactly = 0) { f.engine.respondStreaming(any(), any()) }
    }
    @Test fun `late controls cannot speak reschedule or submit after stopping`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f); f.controller.stopNow(); val spoken = f.speech.size
        f.controller.interrupt(); f.controller.repeatReply(); f.controller.retry()
        f.controller.setTextInput(true); f.controller.setTextInput(false); f.controller.enableTextInput()
        f.controller.refreshVoiceAvailability(); f.controller.reactToTap(); f.controller.snoozeNow(); f.controller.start()
        assertFalse(f.controller.submitText("late")); runCurrent()
        assertEquals(spoken, f.speech.size); assertEquals(1, f.finishes)
        assertFalse(f.controller.canRepeat); assertFalse(f.controller.canInterrupt)
        coVerify(exactly = 0) { f.alarms.scheduleSnooze(any<Long>()) }
    }
    @Test fun `audio focus loss cancels playback and safely offers typing`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.controller.start(); runCurrent(); f.starts.last().invoke()
        f.focusLost!!.invoke(); runCurrent()
        assertEquals(SessionStatus.LISTENING, f.controller.status)
        assertTrue(f.controller.textInputActive)
        assertFalse(f.input.isListening)
        f.completions.last().invoke(); advanceTimeBy(100); runCurrent()
        assertEquals(SessionStatus.LISTENING, f.controller.status)
        assertFalse(f.input.isListening)
    }
    @Test fun `preview has no snooze and can interrupt before audible greeting`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, alarmId = -1)
        f.controller.start(); runCurrent()
        assertFalse(f.controller.snoozeAvailable)
        assertTrue(f.controller.canInterrupt); f.controller.interrupt()
        assertFalse(f.controller.textInputActive); assertTrue(f.input.isListening)
        f.input.say("snooze"); advanceTimeBy(11); runCurrent()
        assertEquals(SessionStatus.LISTENING, f.controller.status)
        coVerify(exactly = 0) { f.alarms.scheduleSnooze(any<Long>()) }
        coVerify(exactly = 0) { f.engine.respond(any(), any()) }
    }
    @Test fun `initially unavailable recognition and denied permission keep startup in typing mode`() = runTest {
        for (available in listOf(false, true)) {
            val f = Fixture(backgroundScope, testScheduler)
            f.input.isAvailable = available
            every { f.app.checkPermission(any(), any(), any()) } returns android.content.pm.PackageManager.PERMISSION_DENIED
            every { f.app.checkSelfPermission(any()) } returns android.content.pm.PackageManager.PERMISSION_DENIED
            ready(f); assertTrue(f.controller.textInputActive); assertFalse(f.controller.voiceAvailable)
            assertEquals(0, f.input.starts)
            f.controller.dispose()
        }
    }
    @Test fun `speech engine initialization exception still displays conversation greeting`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        coEvery { f.speaker.awaitReady(any()) } throws IllegalStateException("bind failed")
        f.controller.start(); runCurrent()
        assertEquals("Good morning", f.controller.aiText)
        assertEquals(SessionStatus.LISTENING, f.controller.status); assertTrue(f.controller.textInputActive)
    }
    @Test fun `startup is single shot and disposal prevents a new greeting`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.controller.start(); f.controller.start(); runCurrent()
        coVerify(exactly = 1) { f.engine.startSession(any()) }
        f.controller.dispose(); f.controller.start(); f.controller.requestStop()
        coVerify(exactly = 1) { f.engine.startSession(any()) }
    }
    @Test fun `challenge cancelled before first model session restarts startup`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, challenge = 1)
        every { f.engine.sessionStarted } returns false
        val greeting = CompletableDeferred<String>()
        coEvery { f.engine.startSession(any()) } coAnswers { greeting.await() }
        f.controller.start(); runCurrent(); f.controller.requestStop()
        assertNotNull(f.controller.activeChallenge)
        coEvery { f.engine.startSession(any()) } returns "Fresh greeting"
        f.controller.dismissChallenge(); runCurrent()
        assertEquals("Fresh greeting", f.controller.aiText)
        assertNull(f.controller.activeChallenge)
        coVerify(exactly = 2) { f.engine.startSession(any()) }
    }
    @Test fun `failed routine startup allows challenge cancellation and keeps recovery screen`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE), challenge = 1)
        coEvery { f.memory.habitsForDate(any()) } throws IllegalStateException("storage")
        f.controller.start(); runCurrent(); f.controller.requestStop()
        assertNotNull(f.controller.activeChallenge)
        f.controller.dismissChallenge()
        assertEquals(SessionStatus.ENDED, f.controller.status); assertTrue(f.controller.conversationFailed)
    }
    @Test fun `fallback speech callbacks work once and cannot replace a later state`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f); f.input.onError!!.invoke(3)
        f.play(); assertEquals(SessionStatus.LISTENING, f.controller.status)
        assertTrue(f.controller.textInputActive)
        f.controller.setTextInput(false); f.input.onError!!.invoke(3)
        val start = f.starts.last(); val complete = f.completions.last()
        f.controller.stopNow(); start(); complete()
        assertEquals(SessionStatus.ENDED, f.controller.status); assertEquals(1, f.finishes)
    }
    @Test fun `failed turn with unavailable speech ends without scheduling farewell`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f); val count = f.speech.size
        every { f.speaker.isReady } returns false
        coEvery { f.engine.respond(any(), any()) } throws IllegalStateException("failed")
        f.input.say("hello"); runCurrent()
        assertTrue(f.controller.conversationFailed); assertEquals(SessionStatus.ENDED, f.controller.status)
        assertEquals(count, f.speech.size)
    }
    @Test fun `priority after midnight uses the current local day`() = runTest {
        var date = java.time.LocalDate.parse("2026-10-10")
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE), today = { date })
        ready(f); date = java.time.LocalDate.parse("2026-10-11"); f.input.say("Tomorrow starts now"); runCurrent()
        coVerify(exactly = 1) { f.memory.savePriority("Tomorrow starts now", date) }
        assertTrue(f.controller.routineFinished)
    }
    @Test fun `routine save ignores duplicate input and cancelling with challenge retains habit`() = runTest {
        val saved = CompletableDeferred<Unit>()
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE, habits = listOf("Water")), challenge = 1)
        coEvery { f.memory.setHabitDone(any(), any(), any()) } coAnswers { saved.await() }
        ready(f); f.input.say("done"); runCurrent(); f.controller.advanceRoutine(true)
        f.controller.requestStop(); saved.complete(Unit); runCurrent()
        assertEquals("Water", f.controller.routineHabit); assertNotNull(f.controller.activeChallenge)
        coVerify(exactly = 1) { f.memory.setHabitDone("Water", any(), true) }
        f.controller.dismissChallenge(); f.controller.setTextInput(true)
        assertTrue(f.controller.textInputActive)
    }
    @Test fun `replaced focus callback remains attached when session is disposed`() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        ready(f)
        val callback: () -> Unit = {}
        every { f.speaker.onFocusLost } returns callback
        f.controller.dispose()
        assertSame(callback, f.speaker.onFocusLost)
    }
    @Test fun `challenge partial and stale replay callbacks cannot survive cancellation`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, challenge = 1)
        ready(f); f.controller.requestStop(); f.controller.setTextInput(true)
        f.controller.startChallengeVoice(); val partial = f.input.onPartialResult!!
        f.input.say("repeat"); val start = f.starts.last(); val complete = f.completions.last()
        f.controller.dismissChallenge(); start(); complete(); partial("ordinary partial")
        assertNull(f.controller.activeChallenge)
        f.controller.requestStop(); f.controller.startChallengeVoice()
        f.controller.submitChallengeAnswer(f.controller.activeChallenge!!.mathQuestion!!.answer.toString())
        partial("late answer"); assertEquals("", f.controller.partialUserText)
        assertFalse(f.controller.submitChallengeAnswer("duplicate"))
        f.controller.dispose(); f.controller.startChallengeVoice(); f.controller.dismissChallenge()
        assertEquals(SessionStatus.ENDED, f.controller.status)
    }
    @Test fun `no audible TTS keeps microphone closed and chooses typing`() = runTest {
        val f = Fixture(backgroundScope, testScheduler, AppSettings(sessionMode = SessionModes.ROUTINE))
        f.controller.start(); runCurrent(); f.completions.last().invoke(); runCurrent()
        assertTrue(f.controller.textInputActive); assertEquals(0, f.input.starts)
    }
}
