package com.anaalarm.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.anaalarm.MainActivity
import com.anaalarm.R
import com.anaalarm.support.Screens
import com.anaalarm.support.TestEnv
import com.anaalarm.support.awaitText
import com.anaalarm.support.awaitTextGone
import com.anaalarm.support.str
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home screen: alarm list rendering, enable/disable, deletion and navigation. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class HomeScreenInstrumentedTest {

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

    private fun open() {
        scenario = Screens.launchHome()
        compose.awaitText(str(R.string.app_name))
    }

    private fun seedAlarm(
        hour: Int = 6,
        minute: Int = 45,
        days: Int = 0,
        snooze: Int = 10,
        enabled: Boolean = true
    ): Long = runBlocking {
        TestEnv.app.memoryStore.upsertAlarm(0, hour, minute, days, snooze, enabled)
    }

    private fun storedAlarm(id: Long) = runBlocking { TestEnv.app.memoryStore.getAlarm(id) }

    @Test
    fun emptyStateExplainsWhatToDoNext() {
        open()

        compose.onNodeWithText(str(R.string.home_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.test_session)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.test_session_desc)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.alarms)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.no_alarm)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.add_alarm)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.settings)).assertIsDisplayed()
    }

    @Test
    fun storedAlarmsReplaceTheEmptyState() {
        seedAlarm(hour = 6, minute = 45, days = 0b0001010) // Monday + Wednesday
        open()

        compose.awaitText("06:45")
        compose.onNodeWithText("06:45").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.next_alarm)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.no_alarm)).assertDoesNotExist()
    }

    @Test
    fun togglingTheSwitchDisablesTheAlarm() {
        val id = seedAlarm()
        open()
        compose.awaitText("06:45")

        compose.onAllNodes(isToggleable()).onFirst().assertIsOn()
        compose.onAllNodes(isToggleable()).onFirst().performClick()

        assertTrue(
            "alarm stayed enabled in the database",
            TestEnv.waitUntil { storedAlarm(id)?.enabled == false }
        )
        compose.waitForIdle()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
    }

    @Test
    fun togglingADisabledAlarmBackOnReschedulesIt() {
        val id = seedAlarm(hour = 23, minute = 55, enabled = false)
        open()
        compose.awaitText("23:55")

        compose.onAllNodes(isToggleable()).onFirst().performClick()

        assertTrue(
            "alarm was not re-enabled",
            TestEnv.waitUntil { storedAlarm(id)?.enabled == true }
        )
    }

    @Test
    fun deleteAsksForConfirmationAndCancelKeepsTheAlarm() {
        val id = seedAlarm()
        open()
        compose.awaitText("06:45")

        compose.onNodeWithContentDescription(str(R.string.delete)).performClick()
        compose.awaitText(str(R.string.cancel))

        compose.onAllNodesWithText(str(R.string.cancel)).filterToOne(hasClickAction()).performClick()
        compose.awaitTextGone(str(R.string.cancel))

        assertEquals(id, storedAlarm(id)?.id)
    }

    @Test
    fun confirmingTheDialogDeletesTheAlarm() {
        val id = seedAlarm()
        open()
        compose.awaitText("06:45")

        compose.onNodeWithContentDescription(str(R.string.delete)).performClick()
        compose.awaitText(str(R.string.cancel))

        compose.onAllNodesWithText(str(R.string.delete)).filterToOne(hasClickAction()).performClick()

        assertTrue(
            "alarm was not deleted",
            TestEnv.waitUntil { storedAlarm(id) == null }
        )
        compose.awaitText(str(R.string.no_alarm))
        assertNull(storedAlarm(id))
    }

    @Test
    fun tappingAnAlarmOpensTheEditorWithItsValues() {
        seedAlarm(hour = 8, minute = 20)
        open()
        compose.awaitText("08:20")

        compose.onNodeWithText("08:20").performClick()

        compose.awaitText(str(R.string.edit_alarm))
        compose.onNodeWithText(str(R.string.edit_alarm)).assertIsDisplayed()
        compose.onNodeWithText("08:20").assertIsDisplayed()
    }

    @Test
    fun addButtonOpensTheNewAlarmEditorAndCancelReturnsHome() {
        open()

        compose.onNodeWithContentDescription(str(R.string.add_alarm)).performClick()
        compose.awaitText(str(R.string.new_alarm))
        compose.onNodeWithText(str(R.string.repeat_days)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.snooze_label)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.cancel)).performClick()

        compose.awaitText(str(R.string.no_alarm))
        assertTrue(runBlocking { TestEnv.app.memoryStore.getEnabledAlarms() }.isEmpty())
    }

    @Test
    fun settingsIconOpensSettingsAndBackReturnsHome() {
        open()

        compose.onNodeWithContentDescription(str(R.string.settings)).performClick()
        compose.awaitText(str(R.string.api_key))

        compose.onNodeWithContentDescription(str(R.string.back)).performClick()

        compose.awaitText(str(R.string.home_title))
        compose.onNodeWithText(str(R.string.home_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.next_alarm)).assertDoesNotExist()
    }

    @Test
    fun disabledAlarmsAreStillListedButSwitchedOff() {
        val id = seedAlarm(hour = 9, minute = 15, enabled = false)
        open()

        compose.awaitText("09:15")
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
        assertFalse(storedAlarm(id)!!.enabled)
    }
}
