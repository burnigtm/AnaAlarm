package com.anaalarm.ui.wakeup

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.anaalarm.R
import com.anaalarm.alarm.AlarmReceiver
import com.anaalarm.alarm.AlarmService
import com.anaalarm.support.FakeAiServer
import com.anaalarm.support.Screens
import com.anaalarm.support.TestEnv
import com.anaalarm.support.awaitText
import com.anaalarm.support.awaitTextField
import com.anaalarm.support.hasTextField
import com.anaalarm.support.str
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The wake-up conversation, end to end: the real activity, the real session controller and the
 * real HTTP stack, answered by a fake DeepSeek. Replies are typed rather than spoken because
 * emulators have no microphone input — the controller treats both identically.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class WakeUpSessionInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = Screens.runtimePermissions()

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var server: FakeAiServer
    private var scenario: ActivityScenario<WakeUpActivity>? = null

    @Before
    fun setUp() {
        TestEnv.clearDatabase()
        TestEnv.resetSettings()
        server = FakeAiServer()
        server.start()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        server.shutdown()
        TestEnv.app.overrideAiBackend(null)
        TestEnv.clearDatabase()
        TestEnv.resetSettings()
    }

    private fun startSessionWithApiKey() = runBlocking {
        TestEnv.app.settingsStore.update(apiKey = "sk-session", name = "Marina", sessionMinutes = 15)
        server.installIntoApp()
        scenario = Screens.launchWakeUp()
    }

    /** The controller only shows the text field once voice input is ruled out. */
    private fun switchToTyping() {
        if (!compose.hasTextField()) {
            // The greeting text can be visible while TTS is still finishing. Wait for the
            // controller's LISTENING transition instead of racing the conditional fallback.
            compose.awaitText(str(R.string.type_instead), timeoutMs = 40_000)
            compose.onNodeWithText(str(R.string.type_instead))
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
        }
        compose.awaitTextField()
    }

    private fun say(text: String) {
        switchToTyping()
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNodeWithText(str(R.string.send)).performClick()
    }

    @Test
    fun screenShowsTheClockAndStopControlImmediately() {
        startSessionWithApiKey()

        compose.awaitText(str(R.string.wake_up_title))
        compose.onNodeWithText(str(R.string.wake_up_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.stop)).assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.snooze_action)).assertCountEquals(0)
    }

    @Test
    fun realAlarmSessionsExposeSnooze() {
        val intent = Intent(TestEnv.context, WakeUpActivity::class.java)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, 42L)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)

        compose.awaitText(str(R.string.snooze_action))
        compose.onNodeWithText(str(R.string.snooze_action)).assertIsDisplayed()
    }

    @Test
    fun withoutAnApiKeyTheSessionFailsWithALocalizedHint() {
        scenario = Screens.launchWakeUp()

        compose.awaitText(str(R.string.wake_up_title))
        compose.awaitText(str(R.string.no_api_key), timeoutMs = 40_000)
        compose.onNodeWithText(str(R.string.no_api_key)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.status_ended)).assertIsDisplayed()
    }

    @Test
    fun overlappingAlarmIntentReplacesTheControllerAndAlarmId() {
        scenario = ActivityScenario.launch(AlarmService.wakeUpIntent(TestEnv.context, 41L))
        compose.awaitText(str(R.string.no_api_key), timeoutMs = 40_000)

        TestEnv.context.startActivity(AlarmService.wakeUpIntent(TestEnv.context, 42L))

        assertTrue(
            "singleTask activity kept the first alarm controller",
            TestEnv.waitUntil(timeoutMs = 15_000) {
                var activeId = -1L
                scenario?.onActivity { activeId = it.activeAlarmIdForTest() }
                activeId == 42L
            }
        )
    }

    @Test
    fun configurationRecreationRetainsTheLiveController() {
        scenario = ActivityScenario.launch(AlarmService.wakeUpIntent(TestEnv.context, 43L))
        compose.awaitText(str(R.string.no_api_key), timeoutMs = 40_000)
        var before = 0
        scenario?.onActivity { before = it.controllerIdentityForTest() }

        scenario?.recreate()

        var after = 0
        scenario?.onActivity { after = it.controllerIdentityForTest() }
        assertEquals(before, after)
    }

    @Test
    fun anaGreetsTheUserAndAnswersTheirReply() {
        server.enqueueReply("Good morning Marina! How did you sleep?")
        server.enqueueReply("Wonderful. What is your plan for today?")
        startSessionWithApiKey()

        compose.awaitText("Good morning Marina! How did you sleep?", timeoutMs = 40_000)

        say("I slept really well")

        compose.awaitText("Wonderful. What is your plan for today?", timeoutMs = 40_000)
        compose.onNodeWithText("${str(R.string.you_said)}: I slept really well").assertIsDisplayed()
    }

    @Test
    fun aStopPhraseAsksTheModelToWrapUp() {
        server.respondAlwaysWith("Have a great day, time to get up!")
        startSessionWithApiKey()
        compose.awaitText("Have a great day, time to get up!", timeoutMs = 40_000)
        server.takeRequest() // the greeting turn

        say("stop")

        val wrapUpAsked = TestEnv.waitUntil(timeoutMs = 30_000) {
            server.takeRequest(2_000)?.body?.readUtf8()?.contains("The wake-up session is over") == true
        }
        assertTrue("wrap-up prompt was never sent", wrapUpAsked)
    }

    @Test
    fun stopButtonEndsTheSessionAndSavesTheDayLog() {
        server.respondAlwaysWith("Good morning Marina!")
        startSessionWithApiKey()
        compose.awaitText("Good morning Marina!", timeoutMs = 40_000)

        compose.onNodeWithText(str(R.string.stop)).performClick()

        assertTrue(
            "wake-up activity did not finish",
            TestEnv.waitUntil(timeoutMs = 20_000) {
                runCatching { scenario?.state }.getOrNull() == Lifecycle.State.DESTROYED
            }
        )
        assertTrue(
            "the conversation was not written to the day log",
            TestEnv.waitUntil(timeoutMs = 20_000) {
                runBlocking { TestEnv.app.memoryStore.todaySummary() }.contains("Good morning Marina!")
            }
        )
    }

    @Test
    fun aBackendFailureIsShownInsteadOfCrashing() {
        server.enqueueHttpError(401)
        startSessionWithApiKey()

        compose.awaitText(str(R.string.invalid_api_key), timeoutMs = 40_000)
        compose.onNodeWithText(str(R.string.status_ended)).assertIsDisplayed()
    }

    @Test
    fun yesterdaysLogIsFedBackIntoTheMorningPrompt() = runBlocking {
        TestEnv.app.memoryStore.saveDailyLog(
            "user: I promised to call my mom",
            java.time.LocalDate.now().minusDays(1)
        )
        server.respondAlwaysWith("Did you call your mom?")
        startSessionWithApiKey()

        compose.awaitText("Did you call your mom?", timeoutMs = 40_000)

        val instructions = server.takeRequest()!!.body.readUtf8()
        assertTrue(instructions.contains("call my mom"))
        assertTrue(instructions.contains("Marina"))
    }
}
