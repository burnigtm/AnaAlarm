package com.anaalarm.voice

import android.content.ComponentName
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anaalarm.debug.ScriptedRecognition
import com.anaalarm.debug.ScriptedRecognitionService
import com.anaalarm.debug.ScriptedTts
import com.anaalarm.support.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptedSpeechInstrumentedTest {
    @get:Rule val permissions = Screens.runtimePermissions()
    @get:Rule val evidence = VoiceTestEvidence { ScriptedTts.trace.toList() }
    private lateinit var input: SpeechListener
    private var tts: TtsManager? = null
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    @Before fun before() {
        ScriptedRecognition.reset(); ScriptedTts.reset()
        input = SpeechListener(TestEnv.context, { true }, ComponentName(TestEnv.context, ScriptedRecognitionService::class.java))
    }
    @After fun after() { input.destroy(); tts?.shutdown() }
    private fun start(language: String = "en-US") {
        main { input.startListening(language) }
        assertTrue("scripted recognizer did not bind", TestEnv.waitUntil { ScriptedRecognition.callback != null })
    }
    @Test fun partialFinalAndActivityTravelThroughThePlatformRecognizer() {
        val partial = java.util.concurrent.CopyOnWriteArrayList<String>()
        val results = java.util.concurrent.CopyOnWriteArrayList<String>()
        val levels = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val activity = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        var ready = false
        input.onReady = { ready = true }; input.onPartialResult = { partial += it }
        input.onResult = { results += it }; input.onLevel = { levels += it }; input.onSpeechActivity = { activity += it }
        start()
        main { ScriptedRecognition.speech(10f); ScriptedRecognition.partial("I slept") }
        assertTrue(TestEnv.waitUntil { partial.size == 1 && ready && levels.isNotEmpty() })
        assertTrue(results.isEmpty()); assertEquals(1f, levels.last(), 0f)
        main { ScriptedRecognition.final(" I slept well ") }
        assertTrue(TestEnv.waitUntil { results.size == 1 })
        assertEquals("I slept well", results.single()); assertFalse(input.isListening)
        assertTrue(TestEnv.waitUntil { activity.contains(false) })
    }
    @Test fun bothLanguagesAndEndpointingExtrasReachTheService() {
        for (language in listOf("en-US", "pt-BR")) {
            start(language)
            assertEquals(language, ScriptedRecognition.request?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            assertTrue(ScriptedRecognition.request!!.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
            main { input.stopListening() }
            assertTrue(TestEnv.waitUntil { ScriptedRecognition.callback == null })
        }
    }
    @Test fun allRecognizerErrorsReachTheWrapperAndEndTheRequest() {
        for (error in (1..15).toList()) {
            var reported: Int? = null
            input.onError = { reported = it }
            start()
            main { ScriptedRecognition.error(error) }
            assertTrue("error $error was lost", TestEnv.waitUntil { reported == error })
            assertFalse(input.isListening); assertEquals(error, input.lastErrorCode)
        }
    }
    @Test fun emptyFinalResultBecomesNoMatch() {
        var error = 0
        input.onError = { error = it }
        start(); main { ScriptedRecognition.final("  ") }
        assertTrue(TestEnv.waitUntil { error == SpeechRecognizer.ERROR_NO_MATCH })
    }
    @Test fun cancellationDropsQueuedResultsAndCanStartANewRequest() {
        val results = java.util.concurrent.CopyOnWriteArrayList<String>()
        input.onResult = { results += it }
        start()
        main { ScriptedRecognition.partial("old"); input.stopListening() }
        assertTrue(TestEnv.waitUntil { ScriptedRecognition.callback == null })
        start(); main { ScriptedRecognition.final("new") }
        assertTrue(TestEnv.waitUntil { results.size == 1 })
        assertEquals(listOf("new"), results.toList())
    }
    @Test fun scriptedTtsEngineProducesRealStartAndCompletionEventsForBothLanguages() {
        main { tts = TtsManager(TestEnv.context, TestEnv.context.packageName) }
        assertTrue(TestEnv.waitUntil { tts!!.isReady })
        for (language in listOf("en", "pt")) {
            var started = false; var completed = false
            main { tts!!.setLanguage(language); tts!!.speak("Good morning", { started = true }, { completed = true }) }
            assertTrue(TestEnv.waitUntil { started && completed })
        }
        assertEquals(2, ScriptedTts.trace.count { it.contains("Good morning") })
    }
    @Test fun synthesisFailureCompletesWithoutClaimingAudiblePlayback() {
        main { tts = TtsManager(TestEnv.context, TestEnv.context.packageName) }
        assertTrue(TestEnv.waitUntil { tts!!.isReady })
        ScriptedTts.fail = true
        var started = false; var completed = false
        main { tts!!.speak("fail", { started = true }, { completed = true }) }
        assertTrue(TestEnv.waitUntil { completed })
        assertFalse(started)
    }
}
