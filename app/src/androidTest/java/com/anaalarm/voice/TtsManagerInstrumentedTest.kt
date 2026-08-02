package com.anaalarm.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.anaalarm.support.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Talks to the device's real text-to-speech engine. The session stalls forever if a completion
 * callback is lost, so the guarantee under test is "speak always calls back".
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TtsManagerInstrumentedTest {

    private lateinit var tts: TtsManager

    @Before
    fun setUp() {
        tts = TtsManager(TestEnv.context)
    }

    @After
    fun tearDown() {
        tts.shutdown()
    }

    private fun ready(): Boolean = runBlocking { tts.awaitReady(15_000) }

    @Test
    fun blankTextCompletesWithoutTouchingTheEngine() {
        val done = CountDownLatch(1)

        tts.speak("   ") { done.countDown() }

        assertTrue(
            "completion for blank text must be immediate",
            done.await(5, TimeUnit.SECONDS)
        )
    }

    @Test
    fun engineBecomesReadyAndReportsIt() {
        val isReady = ready()
        assumeTrue("no TTS engine installed on this device", isReady)
        assertTrue(tts.isReady)
    }

    @Test
    fun speakingAlwaysCallsBack() {
        assumeTrue("no TTS engine installed on this device", ready())
        val done = CountDownLatch(1)

        tts.speak("Good morning") { done.countDown() }

        assertTrue(
            "TTS never reported completion (watchdog should have fired)",
            done.await(50, TimeUnit.SECONDS)
        )
    }

    @Test
    fun utterancesQueuedBeforeInitialisationAreFlushed() {
        val fresh = TtsManager(TestEnv.context)
        val done = CountDownLatch(1)
        try {
            fresh.speak("Queued before the engine bound") { done.countDown() }
            assumeTrue(runBlocking { fresh.awaitReady(15_000) })
            assertTrue(done.await(50, TimeUnit.SECONDS))
        } finally {
            fresh.shutdown()
        }
    }

    @Test
    fun voiceSettingsAreAcceptedForBothLanguages() {
        assumeTrue(ready())

        tts.setLanguage("pt")
        tts.setLanguage("en")
        tts.setLanguage("unknown-code") // falls back instead of throwing
        tts.setPitch(1.05f)
        tts.setPitch(9f)   // clamped
        tts.setSpeechRate(1.0f)
        tts.setSpeechRate(0f) // clamped

        assertTrue(tts.isReady)
    }

    @Test
    fun stopAndShutdownAreIdempotent() {
        tts.stop()
        tts.stop()
        tts.shutdown()
        tts.shutdown()
        tts.stop()
    }

    @Test
    fun shutdownMovesTheManagerToATerminalState() {
        tts.shutdown()

        assertTrue(
            TestEnv.waitUntil(timeoutMs = 5_000) {
                tts.currentState == TtsState.SHUTDOWN
            }
        )
        assertFalse(runBlocking { tts.awaitReady(100) })
    }

    @Test
    fun theEngineStillSpeaksAfterBeingStopped() {
        assumeTrue(ready())
        tts.speak("First sentence") { }
        tts.stop()

        val done = CountDownLatch(1)
        tts.speak("Second sentence") { done.countDown() }

        assertTrue(done.await(50, TimeUnit.SECONDS))
    }
}
