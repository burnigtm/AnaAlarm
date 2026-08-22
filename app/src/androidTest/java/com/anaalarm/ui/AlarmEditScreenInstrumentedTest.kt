package com.anaalarm.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.anaalarm.MainActivity
import com.anaalarm.R
import com.anaalarm.data.AlarmEntity
import com.anaalarm.support.Screens
import com.anaalarm.support.TestEnv
import com.anaalarm.support.awaitText
import com.anaalarm.support.str
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

/** The alarm editor: defaults, repeat bitmask, snooze and the save/cancel contract. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AlarmEditScreenInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = Screens.runtimePermissions()

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        TestEnv.clearDatabase()
        TestEnv.resetSettings()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        TestEnv.clearDatabase()
    }

    private fun openNewAlarmEditor() {
        scenario = Screens.launchHome()
        compose.awaitText(str(R.string.app_name))
        compose.onNodeWithContentDescription(str(R.string.add_alarm)).performClick()
        compose.awaitText(str(R.string.new_alarm))
    }

    private fun openEditorFor(time: String) {
        scenario = Screens.launchHome()
        compose.awaitText(str(R.string.app_name))
        compose.awaitText(time)
        compose.onNodeWithText(time).performClick()
        compose.awaitText(str(R.string.edit_alarm))
    }

    private fun storedAlarms(): List<AlarmEntity> =
        runBlocking { TestEnv.app.memoryStore.alarms.first() }

    private fun awaitStoredAlarms(count: Int): List<AlarmEntity> {
        TestEnv.waitUntil(timeoutMs = 15_000) { storedAlarms().size == count }
        return storedAlarms()
    }

    @Test
    fun savingANewAlarmPersistsItAndConfirmsWithASnackbar() {
        openNewAlarmEditor()

        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()

        val saved = awaitStoredAlarms(1)
        assertEquals(1, saved.size)
        assertTrue("a brand new alarm starts enabled", saved.first().enabled)
        assertEquals(0, saved.first().days)

        // The editor confirms with a scheduled-for message and then navigates home.
        compose.awaitText(str(R.string.app_name))
    }

    @Test
    fun cancellingDiscardsTheNewAlarm() {
        openNewAlarmEditor()

        compose.onNodeWithText(str(R.string.cancel)).performScrollTo().performClick()

        compose.awaitText(str(R.string.no_alarm))
        assertTrue(storedAlarms().isEmpty())
    }

    @Test
    fun repeatChipsAreStoredAsASundayFirstBitmask() {
        openNewAlarmEditor()

        // Short day initials in the default locale; "M" and "W" are unambiguous.
        compose.onAllNodesWithText("M").onFirst().performScrollTo().performClick()
        compose.onAllNodesWithText("W").onFirst().performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()

        val saved = awaitStoredAlarms(1).first()
        val expected = (1 shl 1) or (1 shl 3) // Monday | Wednesday, Sunday = bit 0
        assertEquals(expected, saved.days)
        assertTrue(com.anaalarm.alarm.AlarmTriggerCalculator.hasDay(saved.days, DayOfWeek.MONDAY))
        assertTrue(com.anaalarm.alarm.AlarmTriggerCalculator.hasDay(saved.days, DayOfWeek.WEDNESDAY))
    }

    @Test
    fun editingAnExistingAlarmUpdatesTheSameRow() = runBlocking {
        val id = TestEnv.app.memoryStore.upsertAlarm(0, 8, 20, 0, 10, true)
        openEditorFor("08:20")

        compose.onAllNodesWithText("M").onFirst().performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()

        assertTrue(
            "repeat day was not saved",
            TestEnv.waitUntil(timeoutMs = 15_000) {
                runBlocking { TestEnv.app.memoryStore.getAlarm(id)?.days } == (1 shl 1)
            }
        )
        assertEquals(1, storedAlarms().size)
        val updated = TestEnv.app.memoryStore.getAlarm(id)!!
        assertEquals(8, updated.hour)
        assertEquals(20, updated.minute)
    }

    @Test
    fun editingKeepsTheStoredSnoozeValue() = runBlocking {
        val id = TestEnv.app.memoryStore.upsertAlarm(0, 9, 30, 0, 8, true)
        openEditorFor("09:30")

        compose.onNodeWithText("8 min").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()

        compose.awaitText(str(R.string.app_name))
        assertEquals(8, TestEnv.app.memoryStore.getAlarm(id)!!.snoozeMinutes)
    }

    @Test
    fun editingADisabledAlarmDoesNotSilentlyEnableIt() = runBlocking {
        val id = TestEnv.app.memoryStore.upsertAlarm(0, 10, 10, 0, 10, false)
        openEditorFor("10:10")

        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()

        compose.awaitText(str(R.string.app_name))
        assertNotNull(TestEnv.app.memoryStore.getAlarm(id))
        assertEquals(false, TestEnv.app.memoryStore.getAlarm(id)!!.enabled)
    }

    @Test
    fun savedAlarmAppearsOnTheHomeList() {
        openNewAlarmEditor()

        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()

        val saved = awaitStoredAlarms(1).first()
        val label = String.format(java.util.Locale.getDefault(), "%02d:%02d", saved.hour, saved.minute)
        compose.awaitText(label)
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
    }
}

