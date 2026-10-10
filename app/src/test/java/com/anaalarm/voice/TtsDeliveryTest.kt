package com.anaalarm.voice

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TtsDeliveryTest {
    private val audio = mockk<AudioManager>(relaxed = true)
    private val focuses = mutableListOf<android.media.AudioFocusRequest>()
    private val engine = mockk<TextToSpeech>(relaxed = true)
    private lateinit var init: TextToSpeech.OnInitListener
    private lateinit var progress: UtteranceProgressListener
    private lateinit var manager: TtsManager
    private val ids = mutableListOf<String>()
    private val modes = mutableListOf<Int>()
    private val params = mutableListOf<Bundle>()
    @Before fun before() {
        every { engine.setOnUtteranceProgressListener(any()) } answers { progress = firstArg(); TextToSpeech.SUCCESS }
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } answers {
            ids += arg<String>(3); modes += secondArg<Int>(); params += thirdArg<Bundle>(); TextToSpeech.SUCCESS
        }
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio
        every { audio.requestAudioFocus(any<android.media.AudioFocusRequest>()) } answers { focuses += firstArg<android.media.AudioFocusRequest>(); AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
        manager = TtsManager(context, engineFactory = { _, listener, _ -> init = listener; engine })
    }
    @After fun after() { manager.shutdown(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun focusListener(): AudioManager.OnAudioFocusChangeListener =
        focuses.last().javaClass.getDeclaredMethod("getOnAudioFocusChangeListener").invoke(focuses.last()) as AudioManager.OnAudioFocusChangeListener
    private fun ready() { init.onInit(TextToSpeech.SUCCESS); idle(); assertTrue(manager.isReady) }
    @Test fun queuedBeforeInitFlushesOnlyLatestAndCompletesSupersededRequest() {
        var first = 0; var second = 0
        manager.speak("first", completion = { first++ }); manager.speak("second", completion = { second++ })
        assertEquals(1, first); assertTrue(ids.isEmpty())
        ready(); assertEquals(1, ids.size)
        progress.onStart(ids.single()); progress.onDone(ids.single()); idle()
        assertEquals(1, second)
        assertEquals(AudioManager.STREAM_MUSIC, params.single().getInt(TextToSpeech.Engine.KEY_PARAM_STREAM))
        assertEquals(1f, params.single().getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME), 0f)
    }
    @Test fun initFailureAndShutdownCompleteNewRequestsWithoutPlayback() {
        var completed = 0
        manager.speak("pending", completion = { completed++ })
        init.onInit(TextToSpeech.ERROR); idle()
        manager.speak("failed", completion = { completed++ })
        manager.shutdown(); manager.speak("shutdown", completion = { completed++ }); idle()
        assertEquals(3, completed); assertFalse(manager.isReady)
        assertTrue(ids.isEmpty())
    }
    @Test fun initWatchdogFailsPendingSpeechAndLateInitCannotReviveIt() {
        var completed = 0
        manager.speak("pending", completion = { completed++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13))
        assertEquals(1, completed); assertFalse(manager.isReady)
        init.onInit(TextToSpeech.SUCCESS); idle(); assertFalse(manager.isReady)
    }
    @Test fun playbackStartIsDeliveredOnceAndNullOrUnknownIdsAreIgnored() {
        ready(); var start = 0; var done = 0
        manager.speak("hello", { start++ }, { done++ })
        progress.onStart(null); progress.onDone("unknown"); progress.onStart(ids.single()); progress.onStart(ids.single()); idle()
        assertEquals(1, start); assertEquals(0, done)
        progress.onDone(ids.single()); progress.onDone(ids.single()); idle(); assertEquals(1, done)
    }
    @Test fun bothErrorCallbacksAndStopEventsCompleteExactlyOnce() {
        ready()
        var completed = 0
        for (kind in 0..2) {
            manager.speak("hello", completion = { completed++ })
            when (kind) {
                0 -> progress.onError(ids.last())
                1 -> progress.onError(ids.last(), TextToSpeech.ERROR_SYNTHESIS)
                else -> progress.onStop(ids.last(), true)
            }
            idle()
        }
        progress.onError(null); idle()
        assertEquals(3, completed)
    }
    @Test fun enqueueFailureAndPlaybackWatchdogCompleteWithoutClaimingStart() {
        ready(); var start = 0; var done = 0
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } returns TextToSpeech.ERROR
        manager.speak("fails", { start++ }, { done++ }); idle()
        assertEquals(1, done); assertEquals(0, start)
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } returns TextToSpeech.SUCCESS
        manager.speak("hangs", { start++ }, { done++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, done); assertEquals(0, start)
        verify(atLeast = 1) { engine.stop() }
    }
    @Test fun replacingActiveSpeechCompletesOldButLateCallbacksCannotAffectNewSpeech() {
        ready(); var oldDone = 0; var newDone = 0
        manager.speak("old", completion = { oldDone++ }); val old = ids.last()
        manager.speak("new", completion = { newDone++ }); val new = ids.last(); idle()
        assertEquals(1, oldDone)
        progress.onStart(old); progress.onDone(old); idle(); assertEquals(0, newDone)
        progress.onStart(new); progress.onDone(new); idle(); assertEquals(1, newDone)
    }
    @Test fun stopCancelsSpeechWithoutCompletionAndLateEventsStayIgnored() {
        ready(); var completed = 0
        manager.speak("hello", completion = { completed++ }); val id = ids.single()
        manager.stop(); progress.onStart(id); progress.onDone(id); idle()
        assertEquals(0, completed)
        manager.speak("again", completion = { completed++ }); progress.onDone(ids.last()); idle()
        assertEquals(1, completed)
    }
    @Test fun streamingQueuesFlushThenAppendAndReleaseOnlyAfterBothComplete() {
        ready(); var starts = 0; var done = 0
        manager.speakQueued("First", 10, 1, true, { starts++ }, { done++ })
        manager.speakQueued("Second", 10, 2, false, { starts++ }, { done++ })
        assertEquals(listOf(TextToSpeech.QUEUE_FLUSH, TextToSpeech.QUEUE_ADD), modes)
        ids.forEach { progress.onStart(it); progress.onDone(it) }; idle()
        assertEquals(2, starts); assertEquals(2, done)
    }
    @Test fun emptyQueuedPhraseCompletesAndPreInitPhraseIsDiscarded() {
        var done = 0
        manager.speakQueued("early", 1, 1, completion = { done++ }); ready()
        assertEquals(0, done)
        manager.speakQueued(" ", 1, 2, completion = { done++ }); idle(); assertEquals(1, done)
        assertTrue(ids.isEmpty())
    }
    @Test fun queuedEnqueueFailureWatchdogAndCancellationDoNotHangTheTurn() {
        ready(); var done = 0
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } returns TextToSpeech.ERROR
        manager.speakQueued("failure", 1, 1, completion = { done++ }); idle(); assertEquals(1, done)
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } returns TextToSpeech.SUCCESS
        manager.speakQueued("hang", 1, 2, completion = { done++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5)); assertEquals(2, done)
        manager.speakQueued("cancelled", 2, 1, completion = { done++ }); manager.cancelQueuedTurn(2); idle()
        assertEquals(2, done)
    }
    @Test fun pendingStopAndShutdownDiscardLateInitializationAndAreIdempotent() {
        manager.speak("pending"); manager.stop(); ready(); assertTrue(ids.isEmpty())
        manager.shutdown(); manager.shutdown(); init.onInit(TextToSpeech.SUCCESS); idle(); assertFalse(manager.isReady)
    }
    @Test fun unavailableLanguageFallsBackAndVoiceSettingsAreClamped() {
        every { engine.setLanguage(any()) } returns TextToSpeech.LANG_NOT_SUPPORTED
        every { engine.language } returns null
        ready(); manager.setLanguage("pt"); manager.setPitch(3f); manager.setSpeechRate(0.1f); idle()
        verify { engine.setPitch(2f); engine.setSpeechRate(0.5f); engine.setLanguage(java.util.Locale.forLanguageTag("pt-BR")) }
    }
    @Test fun focusLossStopsPlaybackAndNotifiesTheSessionButDuckingDoesNot() {
        ready(); var lost = 0
        manager.onFocusLost = { lost++ }
        manager.speak("hello")
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        idle(); assertEquals(0, lost)
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle(); assertEquals(1, lost)
        verify(atLeast = 1) { audio.abandonAudioFocusRequest(any()) }
    }
    @Test fun temporaryVolumeRaiseIsRestoredOnlyIfTheUserHasNotChangedIt() {
        ready(); var volume = 0
        every { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 10
        every { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } answers { volume }
        every { audio.setStreamVolume(AudioManager.STREAM_MUSIC, any(), any()) } answers { volume = secondArg() }
        manager.speak("hello"); assertEquals(7, volume)
        progress.onDone(ids.last()); idle(); assertEquals(0, volume)
        manager.speak("hello"); volume = 4
        progress.onDone(ids.last()); idle(); assertEquals(4, volume)
    }
    @Test fun readinessReportsPendingTimeoutFailureReadyAndShutdownStates() = kotlinx.coroutines.test.runTest {
        val timeout = async { manager.awaitReady(100) }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertFalse(timeout.await()); assertFalse(manager.isFailed)
        init.onInit(TextToSpeech.ERROR); idle(); assertTrue(manager.isFailed); assertFalse(manager.awaitReady(100))
        manager.shutdown(); assertFalse(manager.awaitReady(100))
    }
    @Test fun initializedReadyReturnsImmediatelyAndBackgroundCancelAndShutdownInvalidateSpeech() = kotlinx.coroutines.test.runTest {
        ready(); assertTrue(manager.awaitReady(100))
        var done = 0
        manager.speak("active", completion = { done++ }); progress.onStart(ids.last()); idle()
        val thread = Thread { manager.cancelQueuedTurn(42); manager.stop(); manager.shutdown() }
        thread.start(); thread.join(); idle()
        assertEquals(0, done); assertFalse(manager.isReady)
    }
    @Test fun voiceSettingsBeforeReadinessAndNullCallbacksCannotTouchTheEngine() {
        manager.setLanguage("en"); manager.setPitch(1f); idle()
        verify(exactly = 0) { engine.setPitch(any()) }
        ready(); progress.onDone(null); progress.onStop(null, false); progress.onError(null, 0); idle()
        manager.onFocusLost = null; manager.speak("active")
        focuses.last().let { focus ->
            val listener = focus.javaClass.getDeclaredMethod("getOnAudioFocusChangeListener").invoke(focus) as AudioManager.OnAudioFocusChangeListener
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        }
        idle(); verify(atLeast = 1) { engine.stop() }
    }
    @Test fun backgroundQueuedSpeechIsInvalidatedByStopBeforeMainThreadDispatch() {
        ready()
        val thread = Thread { manager.speak("late"); manager.speakQueued("late phrase", 1, 1) }
        thread.start(); thread.join(); manager.stop(); idle()
        assertTrue(ids.isEmpty())
    }
    @Test fun shutdownDiscardsPendingSpeechAndActiveSpeechRegardlessOfPlaybackStart() {
        manager.speak("pending shutdown")
        manager.shutdown(); init.onInit(TextToSpeech.SUCCESS); idle()
        assertTrue(ids.isEmpty()); assertFalse(manager.isReady)
    }
    @Test fun shutdownBeforePlaybackRejectsLateCallbacksWithoutClaimingAudibleSpeech() {
        ready(); var start = 0; var done = 0
        manager.speak("active", { start++ }, { done++ }); val id = ids.last()
        manager.shutdown(); progress.onStart(id); progress.onDone(id); idle()
        assertEquals(0, start); assertEquals(0, done)
    }
    @Test fun replacingAudibleSpeechDoesNotRecordAMissingStartAndShutdownCancelsTheReplacement() {
        ready(); var completed = 0
        manager.speak("first", completion = { completed++ }); progress.onStart(ids.last()); idle()
        manager.speak("second"); progress.onStart(ids.last()); idle()
        assertEquals(1, completed)
        manager.shutdown(); idle(); assertFalse(manager.isReady)
    }
    @Test fun languageFallbackUsesPlatformDefaultWithoutForcingASecondUSLocale() {
        ready(); clearMocks(engine, answers = false)
        every { engine.setLanguage(any()) } returns TextToSpeech.LANG_NOT_SUPPORTED
        every { engine.language } returns java.util.Locale.UK
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.UK)
            manager.setLanguage("pt"); idle()
            verify(exactly = 0) { engine.setLanguage(java.util.Locale.US) }
            verify(exactly = 1) { engine.setLanguage(java.util.Locale.UK) }
            verify(exactly = 1) { engine.setLanguage(java.util.Locale.forLanguageTag("pt-BR")) }
        } finally { java.util.Locale.setDefault(previous) }
    }
}
