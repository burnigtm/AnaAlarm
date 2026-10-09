package com.anaalarm.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anaalarm.MainActivity
import com.anaalarm.R
import com.anaalarm.support.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionUiInstrumentedTest {
    @get:Rule val permissions = Screens.runtimePermissions()
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun before() { TestEnv.resetAll() }
    @After fun after() { scenario?.close(); TestEnv.resetAll() }

    @Test fun alarmEditorRouteAndRepeatDraftSurviveRecreationAndSystemBackReturnsHome() {
        scenario = Screens.launchHome()
        compose.onNodeWithContentDescription(str(R.string.add_alarm)).performClick()
        compose.awaitText(str(R.string.new_alarm))
        compose.onAllNodesWithText("M").onFirst().performScrollTo().performClick()
        scenario!!.recreate()
        compose.awaitText(str(R.string.new_alarm))
        compose.onAllNodesWithText("M").onFirst().assertIsSelected()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.awaitText(str(R.string.no_alarm))
    }

    @Test fun nextAlarmDetailsIntentOpensEditorWithoutStartingConversation(): Unit = runBlocking {
        val id = TestEnv.app.memoryStore.upsertAlarm(0, 8, 20, 0, 10, true)
        scenario = ActivityScenario.launch(Intent(TestEnv.context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_EDIT_ALARM_ID, id))
        compose.awaitText(str(R.string.edit_alarm))
        compose.onNodeWithText("08:20").assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.wake_up_title)).assertCountEquals(0)
    }

    @Test fun habitsAreAvailableBeforeAnyConversation(): Unit = runBlocking {
        TestEnv.app.settingsStore.update(habits = listOf("stretch"))
        scenario = Screens.launchHome()
        compose.awaitText("stretch")
        compose.onNodeWithText("stretch").performScrollTo().performClick()
        assertTrue(TestEnv.waitUntil {
            runBlocking { TestEnv.app.memoryStore.habitsForDate(java.time.LocalDate.now()).any { it.name == "stretch" && it.done } }
        })
    }

    @Test fun memoryCanBeEditedForgottenAndDisabled(): Unit = runBlocking {
        TestEnv.app.memoryStore.remember("Likes hiking", "fact")
        scenario = Screens.launchHome()
        compose.onNodeWithContentDescription(str(R.string.settings)).performClick()
        compose.onNodeWithText(str(R.string.memory_title)).performScrollTo().performClick()
        compose.awaitText("Likes hiking")
        compose.onNodeWithText(str(R.string.memory_edit)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Likes swimming")
        compose.onNodeWithText(str(R.string.save)).performClick()
        compose.awaitText("Likes swimming")
        compose.onNodeWithText(str(R.string.memory_edited)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.memory_forget)).performClick()
        compose.awaitText(str(R.string.memory_empty))
        TestEnv.app.memoryStore.remember("Likes hiking", "fact")
        assertTrue(TestEnv.app.memoryStore.companionMemories.first().isEmpty())
        compose.onNodeWithContentDescription(str(R.string.memory_enabled)).assertIsToggleable().performClick()
        assertTrue(TestEnv.waitUntil { !TestEnv.settings().memoryEnabled })
    }

    @Test fun settingsDraftSurvivesVisitingMemoriesAndRecreation() {
        scenario = Screens.launchHome()
        compose.onNodeWithContentDescription(str(R.string.settings)).performClick()
        compose.onNodeWithText(str(R.string.name_hint)).performScrollTo()
        // The name field is identified by its existing label.
        compose.onNode(hasSetTextAction() and hasText(str(R.string.name_hint))).performTextReplacement("Taylor")
        compose.onNodeWithText(str(R.string.memory_title)).performScrollTo().performClick()
        compose.awaitText(str(R.string.memory_empty))
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.awaitText("Taylor")
        scenario!!.recreate()
        compose.onNodeWithText("Taylor").performScrollTo().assertIsDisplayed()
    }
}
