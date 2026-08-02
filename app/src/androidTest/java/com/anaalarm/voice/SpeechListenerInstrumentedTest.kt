package com.anaalarm.voice

import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.anaalarm.support.TestEnv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Speech recognition against whatever the device actually provides. Emulators usually have no
 * recognizer, so the important guarantee is that the wrapper degrades instead of crashing.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SpeechListenerInstrumentedTest {

    private lateinit var listener: SpeechListener

    @Before
    fun setUp() {
        listener = SpeechListener(TestEnv.context)
    }

    @After
    fun tearDown() {
        listener.stopListening()
        listener.destroy()
    }

    @Test
    fun aFreshListenerIsIdle() {
        assertFalse(listener.isListening)
        assertNull(listener.lastErrorCode)
    }

    @Test
    fun availabilityIsReportedWithoutThrowing() {
        val available = listener.isAvailable
        assertTrue(available || !available)
    }

    @Test
    fun startingWithoutARecognizerReportsAClientError() {
        assumeFalse("device has a recognizer", listener.isAvailable)
        val error = CountDownLatch(1)
        var code: Int? = null
        listener.onError = { received ->
            code = received
            error.countDown()
        }

        listener.startListening("en-US")

        assertTrue("no error callback for a missing recognizer", error.await(10, TimeUnit.SECONDS))
        assertEquals(SpeechRecognizer.ERROR_CLIENT, code)
        assertEquals(SpeechRecognizer.ERROR_CLIENT, listener.lastErrorCode)
        assertFalse(listener.isListening)
    }

    @Test
    fun listeningStartsAndStopsCleanlyWhenARecognizerExists() {
        assumeTrue("no recognizer on this device", listener.isAvailable)

        listener.startListening("en-US")
        TestEnv.waitUntil(timeoutMs = 5_000) { listener.isListening }

        listener.stopListening()

        assertTrue(
            "listener did not return to idle",
            TestEnv.waitUntil(timeoutMs = 5_000) { !listener.isListening }
        )
    }

    @Test
    fun stopAndDestroyAreSafeBeforeAnythingStarted() {
        listener.stopListening()
        listener.stopListening()
        listener.destroy()
        listener.destroy()
        listener.stopListening()
        assertFalse(listener.isListening)
    }

    @Test
    fun everyRecognizerErrorCodeHasAReadableName() {
        val expected = mapOf(
            SpeechRecognizer.ERROR_AUDIO to "ERROR_AUDIO",
            SpeechRecognizer.ERROR_CLIENT to "ERROR_CLIENT",
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to "ERROR_INSUFFICIENT_PERMISSIONS",
            SpeechRecognizer.ERROR_NETWORK to "ERROR_NETWORK",
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to "ERROR_NETWORK_TIMEOUT",
            SpeechRecognizer.ERROR_NO_MATCH to "ERROR_NO_MATCH",
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to "ERROR_RECOGNIZER_BUSY",
            SpeechRecognizer.ERROR_SERVER to "ERROR_SERVER",
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to "ERROR_SPEECH_TIMEOUT",
            12 to "ERROR_LANGUAGE_NOT_SUPPORTED",
            13 to "ERROR_LANGUAGE_UNAVAILABLE"
        )

        expected.forEach { (code, name) ->
            assertEquals(name, SpeechListener.errorName(code))
        }
        assertEquals("UNKNOWN(99)", SpeechListener.errorName(99))
    }

    @Test
    fun theAppExposesARecreatedListenerAfterAFailure() {
        val original = TestEnv.app.speechListener
        TestEnv.app.recreateSpeech()
        val replacement = TestEnv.app.speechListener

        assertFalse(original === replacement)
        assertFalse(replacement.isListening)
    }
}
