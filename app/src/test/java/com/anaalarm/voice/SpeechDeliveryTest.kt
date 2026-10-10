package com.anaalarm.voice

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SpeechDeliveryTest {
    private val engines = mutableListOf<SpeechRecognizer>()
    private val callbacks = mutableListOf<RecognitionListener>()
    private val choices = mutableListOf<Boolean>()
    private lateinit var input: SpeechListener
    @Before fun before() { install(false) }
    @After fun after() { input.destroy(); idle(); unmockkStatic(SpeechRecognizer::class) }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun install(onDevice: Boolean, failCreation: Boolean = false) {
        input = SpeechListener(ApplicationProvider.getApplicationContext(), { true }, recognizerFactory = { device ->
            choices += device
            if (failCreation && device) error("engine creation failed")
            mockk<SpeechRecognizer>(relaxed = true).also { engine ->
                engines += engine
                every { engine.setRecognitionListener(any()) } answers { callbacks += firstArg<RecognitionListener>() }
            }
        }, preferOnDevice = onDevice)
    }
    private fun result(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }
    @Test fun duplicateAndStaleResultsAndPartialsCannotAdvanceAnotherRequest() {
        val results = mutableListOf<String>(); val partials = mutableListOf<String>()
        input.onResult = { results += it }; input.onPartialResult = { partials += it }
        input.startListening("en-US"); val old = callbacks.last()
        input.startListening("pt-BR"); val current = callbacks.last()
        old.onPartialResults(result("old")); old.onResults(result("old")); current.onPartialResults(result("current")); idle()
        assertEquals(listOf("current"), partials); assertTrue(results.isEmpty())
        current.onResults(result(" final ")); current.onResults(result("duplicate")); idle()
        assertEquals(listOf("final"), results)
    }
    @Test fun languageUnavailableFallsBackToDefaultAndDropsRetiredCallbacks() {
        input.destroy(); install(true)
        val results = mutableListOf<String>(); input.onResult = { results += it }
        input.startListening("pt-BR"); val old = callbacks.last()
        old.onError(13); idle()
        assertEquals(listOf(true, false), choices)
        old.onResults(result("stale")); callbacks.last().onResults(result("bom dia")); idle()
        assertEquals(listOf("bom dia"), results)
    }
    @Test fun onDeviceConstructionFailureUsesDefaultEngine() {
        input.destroy(); install(true, true)
        input.startListening("en-US")
        assertEquals(listOf(true, false), choices)
        assertTrue(input.isListening)
    }
    @Test fun emptyMissingAndBlankResultBundlesAreNoMatch() {
        val errors = mutableListOf<Int>(); input.onError = { errors += it }
        for (bundle in listOf(null, Bundle(), result(" "))) {
            input.startListening("en-US"); callbacks.last().onResults(bundle); idle()
        }
        assertEquals(listOf(7, 7, 7), errors)
    }
    @Test fun startFailureAndThrowingAvailabilityDegradeWithoutCrashing() {
        var error = 0; input.onError = { error = it }
        input.startListening("en-US"); input.stopListening()
        every { engines.last().startListening(any()) } throws IllegalStateException()
        input.destroy()
        input = SpeechListener(ApplicationProvider.getApplicationContext(), { throw IllegalStateException("unavailable") })
        input.onError = { error = it }; input.startListening("en-US"); idle()
        assertEquals(SpeechRecognizer.ERROR_CLIENT, error); assertFalse(input.isListening)
    }
    @Test fun speechActivityAndLevelsAreGenerationScopedAndBounded() {
        val levels = mutableListOf<Float>(); val activity = mutableListOf<Boolean>(); var ready = 0
        input.onLevel = { levels += it }; input.onSpeechActivity = { activity += it }; input.onReady = { ready++ }
        input.startListening("en-US"); val callback = callbacks.last()
        callback.onReadyForSpeech(null); callback.onBeginningOfSpeech()
        callback.onRmsChanged(-100f); callback.onRmsChanged(100f); callback.onRmsChanged(Float.NaN)
        callback.onEndOfSpeech(); callback.onBufferReceived(byteArrayOf(1)); callback.onEvent(0, null); idle()
        assertEquals(listOf(0f, 1f, 0f), levels); assertEquals(listOf(true, false), activity); assertEquals(1, ready)
        input.stopListening(); callback.onRmsChanged(2f); callback.onBeginningOfSpeech(); callback.onReadyForSpeech(null)
        assertEquals(3, levels.size); assertEquals(1, ready)
    }
    @Test fun cancellationBeforePostedStartDoesNotCreateAnEngine() {
        val thread = Thread { input.startListening("en-US"); input.stopListening() }
        thread.start(); thread.join(); idle()
        assertTrue(engines.isEmpty()); assertFalse(input.isListening)
    }
    @Test @Config(sdk = [31])
    fun platformCapabilitiesSelectOnDeviceDefaultAndUnavailablePaths() {
        input.destroy(); mockkStatic(SpeechRecognizer::class)
        fun engine(): SpeechRecognizer = mockk<SpeechRecognizer>(relaxed = true).also { created ->
            engines += created
            every { created.setRecognitionListener(any()) } answers { callbacks += firstArg<RecognitionListener>() }
        }
        every { SpeechRecognizer.createSpeechRecognizer(any()) } answers { engine() }
        every { SpeechRecognizer.createOnDeviceSpeechRecognizer(any()) } answers { engine() }
        for (onDevice in listOf(false, true)) {
            every { SpeechRecognizer.isOnDeviceRecognitionAvailable(any()) } returns onDevice
            every { SpeechRecognizer.isRecognitionAvailable(any()) } returns true
            input = SpeechListener(ApplicationProvider.getApplicationContext())
            assertTrue(input.isAvailable); input.startListening("en-US")
            callbacks.last().onReadyForSpeech(null); callbacks.last().onResults(result("hello")); idle()
            assertFalse(input.isListening); input.destroy()
        }
        verify { SpeechRecognizer.createSpeechRecognizer(any()); SpeechRecognizer.createOnDeviceSpeechRecognizer(any()) }
        every { SpeechRecognizer.isOnDeviceRecognitionAvailable(any()) } returns false
        every { SpeechRecognizer.isRecognitionAvailable(any()) } returns false
        input = SpeechListener(ApplicationProvider.getApplicationContext())
        assertFalse(input.isAvailable); input.startListening("en-US"); idle(); assertEquals(5, input.lastErrorCode)
        every { SpeechRecognizer.isOnDeviceRecognitionAvailable(any()) } throws IllegalStateException("service unavailable")
        assertFalse(input.isAvailable)
    }
    @Test fun creationAndStartFailuresDeliverClientErrorWithoutLeakingListeningState() {
        input.destroy(); mockkStatic(SpeechRecognizer::class)
        every { SpeechRecognizer.isRecognitionAvailable(any()) } returns true
        every { SpeechRecognizer.createSpeechRecognizer(any()) } throws IllegalStateException("binding")
        input = SpeechListener(ApplicationProvider.getApplicationContext())
        input.startListening("en-US"); idle(); assertEquals(5, input.lastErrorCode); assertFalse(input.isListening)
        input.destroy()
        val engine = mockk<SpeechRecognizer>(relaxed = true)
        every { engine.startListening(any()) } throws IllegalStateException("start")
        every { SpeechRecognizer.createSpeechRecognizer(any()) } returns engine
        input = SpeechListener(ApplicationProvider.getApplicationContext())
        input.startListening("en-US"); idle(); assertEquals(5, input.lastErrorCode); assertFalse(input.isListening)
    }
    @Test fun stoppedLanguageFallbackDoesNotStartAnotherEngineAndBackgroundCleanupIsSafe() {
        input.destroy(); install(true)
        input.startListening("en-US"); callbacks.last().onError(12); input.stopListening(); idle()
        assertEquals(listOf(true), choices)
        val thread = Thread { input.stopListening(); input.destroy() }
        thread.start(); thread.join(); idle(); assertFalse(input.isListening)
    }
    @Test fun emptyPartialLateEndAndOnDeviceNonLanguageErrorsDoNotSubmitResults() {
        input.destroy(); install(true)
        var results = 0; input.onResult = { results++ }
        input.startListening("en-US"); val callback = callbacks.last()
        callback.onPartialResults(Bundle()); callback.onError(3); callback.onError(9); idle()
        callback.onEndOfSpeech(); callback.onResults(result("late")); idle()
        assertEquals(0, results); assertEquals(3, input.lastErrorCode)
        assertEquals(listOf(true), choices)
    }
    @Test fun repeatedStopDestroyAndLateEventsAreSafe() {
        input.startListening("en-US"); val callback = callbacks.last()
        input.destroy(); input.destroy(); input.stopListening(); callback.onError(7); callback.onResults(result("late")); idle()
        assertFalse(input.isListening); assertNull(input.onResult)
    }
    @Test fun queuedPartialCancelledBeforeDeliveryAndUnobservedSignalsAreSafe() {
        input.startListening("en-US"); val callback = callbacks.last()
        callback.onPartialResults(result("queued")); input.stopListening(); idle()
        input.startListening("en-US"); val current = callbacks.last()
        current.onBeginningOfSpeech(); current.onRmsChanged(4f); current.onEndOfSpeech(); idle()
        assertTrue(input.isListening)
    }
}
