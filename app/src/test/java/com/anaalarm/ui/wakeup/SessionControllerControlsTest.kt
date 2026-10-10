package com.anaalarm.ui.wakeup

import android.app.Application
import com.anaalarm.AnaAlarmApp
import com.anaalarm.ai.ConversationEngine
import com.anaalarm.ai.ApiException
import com.anaalarm.data.AlarmEntity
import com.anaalarm.data.AppSettings
import com.anaalarm.voice.TtsManager
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
class SessionControllerControlsTest {
    private class Fixture(scope: CoroutineScope, challenge: Int = 0, voice: Boolean = false) {
        val app = mockk<AnaAlarmApp>(relaxed = true)
        val engine = mockk<ConversationEngine>(relaxed = true)
        val tts = mockk<TtsManager>(relaxed = true)
        val completions = mutableListOf<() -> Unit>()
        var finishes = 0
        val controller: SessionController
        init {
            every { app.settingsStore.settings } returns flowOf(AppSettings())
            every { app.newConversationEngine() } returns engine
            every { app.ttsManager } returns tts
            every { app.speechOutput } returns tts
            every { app.speechInput } returns app.speechListener
            every { app.applicationScope } returns scope
            every { app.speechListener.isAvailable } returns voice
            every { tts.isReady } returns true
            coEvery { tts.awaitReady(any()) } returns true
            coEvery { app.memoryStore.getAlarm(12L) } returns AlarmEntity(id = 12, hour = 7, minute = 0, challengeType = challenge)
            coEvery { engine.startSession(any()) } returns "Good morning"
            every { tts.speak(any(), any(), any()) } answers {
                secondArg<() -> Unit>().invoke()
                completions += thirdArg<() -> Unit>()
            }
            controller = SessionController(app, scope, { finishes++ }, voice, alarmId = 12)
        }
    }

    @Test fun `interruption invalidates late speech completion and repeated taps`() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.start(); runCurrent()
        assertEquals(SessionStatus.SPEAKING, f.controller.status)
        val oldCompletion = f.completions.single()
        f.controller.interrupt()
        f.controller.interrupt()
        assertEquals(SessionStatus.LISTENING, f.controller.status)
        oldCompletion(); advanceTimeBy(200); runCurrent()
        assertEquals(SessionStatus.LISTENING, f.controller.status)
        assertTrue(f.controller.textInputActive)
        f.controller.dispose()
    }

    @Test fun `typed dismissal opens challenge instead of finishing`() = runTest {
        val f = Fixture(backgroundScope, challenge = 1)
        f.controller.start(); runCurrent()
        f.controller.interrupt()
        assertTrue(f.controller.submitText("I am up"))
        assertNotNull(f.controller.activeChallenge)
        assertEquals(0, f.finishes)
        assertFalse(f.controller.submitText("ignored"))
        f.controller.dispose()
    }

    @Test fun `retry keeps failed session available and resends the accepted turn`() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.start(); runCurrent(); f.controller.interrupt()
        coEvery { f.engine.respond("hello", any()) } throws ApiException("network error")
        assertTrue(f.controller.submitText("hello")); runCurrent()
        assertTrue(f.controller.retryAvailable)
        coEvery { f.engine.respond("hello", any()) } returns "Welcome back"
        f.controller.retry(); f.controller.retry(); runCurrent()
        assertFalse(f.controller.retryAvailable)
        assertEquals("Welcome back", f.controller.aiText)
        assertEquals(0, f.finishes)
        coVerify(exactly = 2) { f.engine.respond("hello", any()) }
        f.controller.dispose()
    }

    @Test fun `voice mode remains disabled when permission or recognizer is absent`() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.start(); runCurrent(); f.controller.interrupt()
        f.controller.setTextInput(false)
        assertTrue(f.controller.textInputActive)
        val speech = f.app.speechListener
        verify(exactly = 0) { speech.startListening(any()) }
        f.controller.dispose()
    }

    @Test fun `replay adds no model request and late callback cannot resume stopped session`() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.start(); runCurrent(); f.controller.interrupt()
        f.controller.repeatReply()
        assertEquals(SessionStatus.SPEAKING, f.controller.status)
        f.controller.stopNow()
        f.completions.last().invoke(); advanceTimeBy(200); runCurrent()
        assertEquals(SessionStatus.ENDED, f.controller.status)
        assertEquals(1, f.finishes)
        coVerify(exactly = 0) { f.engine.respond(any(), any()) }
        f.controller.dispose()
    }

    @Test fun `stop during alarm loading waits for configured challenge`() = runTest {
        val f = Fixture(backgroundScope, challenge = 1)
        coEvery { f.app.memoryStore.getAlarm(12L) } coAnswers {
            delay(200L)
            AlarmEntity(id = 12, hour = 7, minute = 0, challengeType = 1)
        }
        f.controller.start(); runCurrent()
        f.controller.requestStop()
        assertEquals(0, f.finishes)
        advanceTimeBy(201L); runCurrent()
        assertNotNull(f.controller.activeChallenge)
        assertEquals(0, f.finishes)
        f.controller.dispose()
    }

    @Test fun `pending stop still finishes when alarm storage is unavailable`() = runTest {
        val f = Fixture(backgroundScope)
        coEvery { f.app.memoryStore.getAlarm(12L) } coAnswers {
            delay(200L)
            throw IllegalStateException("storage unavailable")
        }
        f.controller.start(); runCurrent()
        f.controller.requestStop()
        advanceTimeBy(201L); runCurrent()
        assertEquals(1, f.finishes)
        assertEquals(SessionStatus.ENDED, f.controller.status)
        f.controller.dispose()
    }
}
