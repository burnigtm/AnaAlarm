package com.anaalarm.ui.wakeup

import android.content.ComponentName
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anaalarm.R
import com.anaalarm.alarm.AlarmService
import com.anaalarm.data.SessionModes
import com.anaalarm.debug.ScriptedRecognition
import com.anaalarm.debug.ScriptedRecognitionService
import com.anaalarm.support.*
import com.anaalarm.voice.SpeechListener
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class VoiceCompanionInstrumentedTest {
    @get:Rule val permissions = Screens.runtimePermissions()
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var speaker: ControlledSpeaker
    @get:Rule val evidence = VoiceTestEvidence { if (::speaker.isInitialized) speaker.trace.toList() else emptyList() }
    private lateinit var server: FakeAiServer
    private var scenario: ActivityScenario<WakeUpActivity>? = null
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    @Before fun before() {
        TestEnv.resetAll(); ScriptedRecognition.reset()
        speaker = ControlledSpeaker()
        TestEnv.app.overrideVoice(SpeechListener(TestEnv.context, { true }, ComponentName(TestEnv.context, ScriptedRecognitionService::class.java)), speaker)
        server = FakeAiServer(); server.start(); server.installIntoApp()
    }
    @After fun after() {
        scenario?.close(); TestEnv.app.overrideVoice(null, null)
        server.shutdown(); TestEnv.resetAll()
    }
    private fun launch(routine: Boolean = false, language: String = "en", challenge: Int = 0, streaming: Boolean = false) = runBlocking {
        TestEnv.app.settingsStore.update(apiKey = if (routine) "" else "sk-test", memoryEnabled = false,
            streamingEnabled = streaming, language = language, sessionMode = if (routine) SessionModes.ROUTINE else SessionModes.CONVERSATION,
            habits = if (routine) listOf("Water", "Walk") else emptyList())
        if (challenge != 0) TestEnv.app.memoryStore.upsertAlarm(id = 77L, hour = 7, minute = 0, days = 0,
            snoozeMinutes = 10, enabled = true, challengeType = challenge)
        scenario = if (challenge != 0) ActivityScenario.launch(AlarmService.wakeUpIntent(TestEnv.context, 77L)) else Screens.launchWakeUp()
        observeSessionTrace()
        play()
    }
    private fun observeSessionTrace() {
        scenario!!.onActivity { activity ->
            val controller = checkNotNull(activity.sessionControllerForTest())
            activity.lifecycleScope.launch {
                snapshotFlow {
                    "state=${controller.status};reaction=${controller.buddyReaction};ready=${controller.microphoneReady};" +
                        "speech=${controller.speechActive};partial=${controller.partialUserText};user=${controller.lastUserText};" +
                        "reply=${controller.aiText};habit=${controller.routineHabit};finished=${controller.routineFinished};" +
                        "challenge=${controller.activeChallenge?.type};solved=${controller.challengeSolved}"
                }.collect { speaker.trace += "${android.os.SystemClock.elapsedRealtime()}:$it" }
            }
        }
    }
    private fun play() {
        assertTrue("no queued reply", TestEnv.waitUntil(15_000) { speaker.utterances.isNotEmpty() })
        main { speaker.playNext() }
        assertTrue("microphone did not reopen", TestEnv.waitUntil { ScriptedRecognition.callback != null })
    }
    private fun say(text: String) {
        assertTrue(TestEnv.waitUntil { ScriptedRecognition.callback != null })
        main { ScriptedRecognition.final(text) }
    }
    @Test fun englishSpeechProducesReplyTtsReactionAndDurableTranscript() {
        server.enqueueReply("Good morning"); server.enqueueReply("A good start"); server.enqueueReply("A productive morning")
        launch()
        main { ScriptedRecognition.speech(4f); ScriptedRecognition.partial("I slept") }
        compose.awaitText("I slept")
        val firstRequest = server.takeRequest()!!.body.readUtf8()
        assertFalse(firstRequest.contains("I slept"))
        say("I slept well")
        assertTrue(TestEnv.waitUntil { speaker.utterances.any { it.text == "A good start" } })
        assertTrue(server.takeRequest()!!.body.readUtf8().contains("I slept well"))
        play(); compose.awaitText("A good start")
        compose.waitUntil(3000) {
            compose.onNodeWithTag(WAKE_BUDDY_TEST_TAG).fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" } == str(R.string.reaction_listening)
        }
        compose.onNodeWithTag(WAKE_BUDDY_TEST_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.reaction_listening)))
        say("My priority is family"); play()
        val nextTurn = server.takeRequest()!!.body.readUtf8()
        assertTrue(nextTurn.contains("I slept well") && nextTurn.contains("My priority is family"))
        main { scenario!!.onActivity { it.finish() } }
        assertTrue(TestEnv.waitUntil { runBlocking { TestEnv.app.memoryStore.todaySummary() }.contains("I slept well") })
    }
    @Test fun portugueseSpeechUsesPortugueseRecognitionAndOutputSettings() {
        server.enqueueReply("Bom dia"); server.enqueueReply("Que bom"); server.enqueueReply("Vamos nessa")
        launch(language = "pt")
        assertEquals("pt-BR", ScriptedRecognition.request!!.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals("pt", speaker.languageCode)
        say("Dormi bem"); play(); compose.awaitText("Que bom")
        server.takeRequest()
        assertTrue(server.takeRequest()!!.body.readUtf8().contains("Dormi bem"))
        say("Vou caminhar"); play(); compose.awaitText("Vamos nessa")
        val followUp = server.takeRequest()!!.body.readUtf8()
        assertTrue(followUp.contains("Dormi bem") && followUp.contains("Vou caminhar"))
        assertEquals(3, server.requestCount)
    }
    @Test fun repeatAndTypingAreLocalAndDoNotCreateAnotherModelRequest() {
        server.enqueueReply("Good morning")
        launch(); assertNotNull(server.takeRequest())
        say("repeat")
        assertTrue(TestEnv.waitUntil { speaker.utterances.any { it.text == "Good morning" } })
        play(); say("switch to typing")
        compose.awaitTextField()
        assertNull(server.takeRequest(300))
    }
    @Test fun routinePersistsVoiceCompletionSkipAndPriorityWithoutAnApiKey() {
        launch(routine = true)
        say("done"); play()
        assertTrue(runBlocking { TestEnv.app.memoryStore.habitsForDate(LocalDate.now()) }.any { it.name == "Water" && it.done })
        say("skip"); play()
        say("Finish the presentation"); play()
        assertEquals("Finish the presentation", runBlocking { TestEnv.app.memoryStore.priority() }!!.content)
        assertFalse(runBlocking { TestEnv.app.memoryStore.habitsForDate(LocalDate.now()) }.any { it.name == "Walk" && it.done })
        assertNull(server.takeRequest(300))
        compose.awaitText(str(R.string.routine_finished))
    }
    @Test fun routineRecreationRetainsProgressAndDoesNotReplayCompletedHabits() {
        launch(routine = true); say("done"); play()
        val count = ScriptedRecognition.requests
        scenario!!.recreate()
        observeSessionTrace()
        compose.awaitText(TestEnv.context.getString(R.string.routine_habit_prompt, "Walk"))
        assertEquals(count, ScriptedRecognition.requests)
    }
    @Test fun hardSpeechErrorFallsBackToTypingAndReassuresTheUser() {
        server.enqueueReply("Good morning"); launch()
        main { ScriptedRecognition.error(android.speech.SpeechRecognizer.ERROR_AUDIO) }
        assertTrue(TestEnv.waitUntil { speaker.utterances.isNotEmpty() })
        main { speaker.playNext() }
        compose.awaitTextField()
        assertNull(ScriptedRecognition.callback)
    }
    @Test fun voiceStopOpensMathChallengeAndSpokenAnswerCompletesIt() {
        server.enqueueReply("Good morning"); launch(challenge = 1)
        say("stop"); compose.awaitText(str(R.string.challenge_title))
        var expected = ""
        scenario!!.onActivity { expected = it.sessionControllerForTest()!!.activeChallenge!!.mathQuestion!!.answer.toString() }
        compose.onNodeWithText(str(R.string.challenge_voice)).performClick()
        say(expected)
        assertTrue(TestEnv.waitUntil { scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED })
    }
    @Test fun spokenMemoryAnswerUsesTheExactDisplayedCode() {
        server.enqueueReply("Good morning"); launch(challenge = 2)
        say("stop"); compose.awaitText(str(R.string.challenge_ready))
        var code = ""
        scenario!!.onActivity { code = it.sessionControllerForTest()!!.revealMemoryCodeForDisplay() }
        compose.onNodeWithText(str(R.string.challenge_ready)).performClick()
        compose.onNodeWithText(str(R.string.challenge_voice)).performClick()
        say(code.toCharArray().joinToString(" "))
        assertTrue(TestEnv.waitUntil { scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED })
    }
    @Test fun streamedSpeechWaitsForPlaybackBeforeReopeningTheMicrophoneInBothLanguages() {
        for (language in listOf("en", "pt")) {
            server.enqueueReply("Greeting")
            launch(language = language, streaming = true)
            server.enqueueStream("""data: {"type":"response.output_text.delta","delta":"Here is your morning plan.","sequence_number":1}

data: {"type":"response.output_text.delta","delta":" Have a wonderful and productive day.","sequence_number":2}

data: {"type":"response.completed","response":{"status":"completed"},"sequence_number":3}

""")
            say(if (language == "pt") "Meu plano" else "My plan")
            assertTrue(TestEnv.waitUntil { speaker.utterances.isNotEmpty() })
            assertNull(ScriptedRecognition.callback)
            assertTrue(TestEnv.waitUntil { speaker.utterances.size >= 2 })
            main { speaker.playNext() }
            assertNull(ScriptedRecognition.callback)
            main { speaker.playNext() }
            assertTrue(TestEnv.waitUntil { ScriptedRecognition.callback != null })
            server.takeRequest()
            assertTrue(server.takeRequest()!!.body.readUtf8().contains("\"stream\":true"))
            server.enqueueStream("""data: {"type":"response.output_text.delta","delta":"Continue your morning with a glass of water.","sequence_number":1}

data: {"type":"response.completed","response":{"status":"completed"},"sequence_number":2}

""")
            val followUp = if (language == "pt") "Vou caminhar" else "I will take a walk"
            say(followUp)
            assertTrue(TestEnv.waitUntil { speaker.utterances.isNotEmpty() })
            main { speaker.playNext() }
            assertTrue(TestEnv.waitUntil { ScriptedRecognition.callback != null })
            val request = server.takeRequest()!!.body.readUtf8()
            assertTrue(request.contains(followUp))
            assertTrue(request.contains(if (language == "pt") "Meu plano" else "My plan"))
            assertTrue(request.contains("\"stream\":true"))
            scenario!!.close(); scenario = null
        }
    }
    @Test fun interruptionIgnoresLateCompletionAndFocusLossOffersTyping() {
        server.enqueueReply("Good morning"); launch()
        say("repeat")
        assertTrue(TestEnv.waitUntil { speaker.utterances.isNotEmpty() })
        val old = speaker.utterances.first()
        main { old.start() }
        compose.onNodeWithText(str(R.string.your_turn)).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertTrue(TestEnv.waitUntil { ScriptedRecognition.callback != null })
        main { old.done() }
        assertTrue(ScriptedRecognition.callback != null)
        say("repeat")
        assertTrue(TestEnv.waitUntil { speaker.utterances.isNotEmpty() })
        main { speaker.utterances.first().start(); speaker.onFocusLost!!.invoke() }
        compose.awaitTextField()
        assertNull(ScriptedRecognition.callback)
    }
    @Test fun negatedCommandsAndOrdinarySentencesAreSentToTheModel() {
        server.enqueueReply("Good morning"); server.enqueueReply("Understood")
        launch(); say("Don't say goodbye yet")
        play(); compose.awaitText("Understood")
        server.takeRequest()
        assertTrue(server.takeRequest()!!.body.readUtf8().contains("Don't say goodbye yet"))
    }
    @Test fun voiceSnoozeSchedulesTheSameAlarmAndEnforcesItsCap() = runBlocking {
        TestEnv.app.memoryStore.upsertAlarm(id = 77L, hour = 7, minute = 0, days = 0,
            snoozeMinutes = 10, enabled = true, maxSnoozes = 1)
        TestEnv.app.settingsStore.update(apiKey = "sk-test", memoryEnabled = false)
        server.enqueueReply("Good morning")
        scenario = ActivityScenario.launch(AlarmService.wakeUpIntent(TestEnv.context, 77L))
        play(); say("snooze")
        assertTrue(TestEnv.waitUntil { scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED })
        scenario!!.close(); scenario = null
        server.enqueueReply("Good morning again")
        scenario = ActivityScenario.launch(AlarmService.wakeUpIntent(TestEnv.context, 77L))
        play(); say("snooze")
        compose.awaitText(str(R.string.no_snoozes_left))
        assertTrue(TestEnv.waitUntil { ScriptedRecognition.callback != null })
        assertNotEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario!!.state)
    }
    @Test fun wrongSpokenChallengeAnswerStaysOpenAndCanBeCancelled() {
        server.enqueueReply("Good morning"); launch(challenge = 1)
        say("stop"); compose.awaitText(str(R.string.challenge_title))
        compose.onNodeWithText(str(R.string.challenge_voice)).performClick(); say("minus seven")
        compose.awaitText(str(R.string.challenge_wrong))
        compose.onNodeWithText(str(R.string.cancel)).performClick()
        assertTrue(TestEnv.waitUntil { ScriptedRecognition.callback != null })
    }
}
