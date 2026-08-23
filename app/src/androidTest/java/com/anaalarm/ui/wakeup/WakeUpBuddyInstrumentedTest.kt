package com.anaalarm.ui.wakeup

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.anaalarm.R
import com.anaalarm.alarm.AlarmReceiver
import com.anaalarm.alarm.DismissalChallenges
import com.anaalarm.support.FakeAiServer
import com.anaalarm.support.Screens
import com.anaalarm.support.TestEnv
import com.anaalarm.support.awaitText
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
 * The animated wake-up buddy on the wake screen and inside the stop-challenge dialog: it must
 * appear immediately, mirror the stored species defensively, stay visible through failed and
 * ended sessions, and react inside the dismissal challenge without disturbing the
 * safety-critical Stop/Snooze controls.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class WakeUpBuddyInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = Screens.runtimePermissions()

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<com.anaalarm.ui.wakeup.WakeUpActivity>? = null
    private var server: FakeAiServer? = null

    @Before
    fun setUp() {
        TestEnv.clearDatabase()
        TestEnv.resetSettings()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        server?.shutdown()
        server = null
        TestEnv.app.overrideAiBackend(null)
        TestEnv.clearDatabase()
        TestEnv.resetSettings()
    }

    /** Cold-start sessions without an API key fail fast to ENDED but keep the screen open. */
    private fun launchSession(alarmId: Long = -1L) {
        val intent = Intent(TestEnv.context, com.anaalarm.ui.wakeup.WakeUpActivity::class.java)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)
    }

    private fun storedWakeBuddy(): String? {
        var species: String? = null
        scenario?.onActivity { species = it.buddySpeciesForTest() }
        return species
    }

    @Test
    fun wakeScreenShowsTheBuddyThroughAColdStartFailure() {
        launchSession()

        compose.awaitText(str(R.string.no_api_key), timeoutMs = 40_000)

        compose.onNodeWithTag(WAKE_BUDDY_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.buddy_label)).assertIsDisplayed()
    }

    @Test
    fun buddyMirrorsTheStoredSpeciesSelection() {
        runBlocking { TestEnv.app.settingsStore.update(avatar = com.anaalarm.ui.avatar.Avatars.DINO) }
        launchSession()

        compose.awaitText(str(R.string.no_api_key), timeoutMs = 40_000)

        assertTrue(
            "controller never picked up the dino setting",
            TestEnv.waitUntil(timeoutMs = 10_000) { storedWakeBuddy() == "dino" }
        )
        compose.onNodeWithTag(WAKE_BUDDY_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun corruptStoredSpeciesFallsBackToTheDefaultBuddy() {
        // update() stores raw values; the defensive parse must rescue rendering at read time.
        runBlocking { TestEnv.app.settingsStore.update(avatar = "dragon") }
        launchSession()

        compose.awaitText(str(R.string.no_api_key), timeoutMs = 40_000)

        assertEquals(com.anaalarm.ui.avatar.Avatars.DEFAULT, storedWakeBuddy())
    }

    @Test
    fun buddyStaysVisibleWhenARealAlarmSessionExposesSnooze() {
        runBlocking {
            TestEnv.app.memoryStore.upsertAlarm(
                id = 45L,
                hour = 7,
                minute = 30,
                days = 0,
                snoozeMinutes = 10,
                enabled = true
            )
        }
        launchSession(alarmId = 45L)

        compose.awaitText(str(R.string.snooze_action), timeoutMs = 20_000)

        compose.onNodeWithTag(WAKE_BUDDY_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(WAKE_SNOOZE_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun stopChallengeDialogShowsItsOwnBuddyAndSurvivesAWrongAnswer() {
        runBlocking {
            // A live session is required: an already-ENDED session bypasses the challenge
            // by design (Stop must always stay reachable after failures).
            TestEnv.app.settingsStore.update(apiKey = "sk-buddy")
            val fake = FakeAiServer()
            fake.start()
            fake.installIntoApp()
            fake.enqueueReply("Good morning! Ready when you are.")
            server = fake
            TestEnv.app.memoryStore.upsertAlarm(
                id = 46L,
                hour = 6,
                minute = 45,
                days = 0,
                snoozeMinutes = 10,
                enabled = true,
                challengeType = DismissalChallenges.Type.MATH.code
            )
        }
        launchSession(alarmId = 46L)
        compose.awaitText("Good morning! Ready when you are.", timeoutMs = 40_000)

        // Accessibility action rather than raw touch: the wake window owns system flags.
        compose.onNodeWithTag(WAKE_STOP_TEST_TAG)
            .performSemanticsAction(SemanticsActions.OnClick)

        compose.awaitText(str(R.string.challenge_title))
        compose.onNodeWithTag(CHALLENGE_BUDDY_TEST_TAG).assertIsDisplayed()

        // A wrong answer regenerates the question; the buddy reacts but the dialog holds.
        compose.onNodeWithText(str(R.string.challenge_answer_hint))
            .performTextReplacement("999")
        compose.onNodeWithText(str(R.string.submit)).performClick()

        compose.awaitText(str(R.string.challenge_wrong))
        compose.onNodeWithTag(CHALLENGE_BUDDY_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.challenge_title)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.cancel)).performClick()
        compose.onNodeWithTag(WAKE_STOP_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun memoryChallengeShowsTheBuddyWhileTheCodeIsVisible() {
        runBlocking {
            TestEnv.app.settingsStore.update(apiKey = "sk-buddy")
            val fake = FakeAiServer()
            fake.start()
            fake.installIntoApp()
            fake.enqueueReply("Good morning! Let us begin.")
            server = fake
            TestEnv.app.memoryStore.upsertAlarm(
                id = 47L,
                hour = 5,
                minute = 55,
                days = 0,
                snoozeMinutes = 10,
                enabled = true,
                challengeType = DismissalChallenges.Type.MEMORY.code
            )
        }
        launchSession(alarmId = 47L)
        compose.awaitText("Good morning! Let us begin.", timeoutMs = 40_000)

        compose.onNodeWithTag(WAKE_STOP_TEST_TAG)
            .performSemanticsAction(SemanticsActions.OnClick)

        // Memorize phase: the buddy stays attentive beside the code prompt.
        compose.awaitText(str(R.string.challenge_memory_show))
        compose.onNodeWithTag(CHALLENGE_BUDDY_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.challenge_ready)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.cancel)).performClick()
        compose.onNodeWithTag(WAKE_STOP_TEST_TAG).assertIsDisplayed()
    }
}
