package com.anaalarm.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.anaalarm.support.str
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System Back from nested screens must return to Home (not finish MainActivity).
 * Fails if AppRoot lacks BackHandler.
 *
 * Dispatches through [androidx.activity.OnBackPressedDispatcher] rather than
 * UiDevice.pressBack(): Compose BackHandler is registered on that dispatcher, and
 * UiAutomator key injection returns false under CI emulators even when the app is focused.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AppRootBackNavigationInstrumentedTest {

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
        TestEnv.resetSettings()
    }

    private fun pressSystemBack() {
        val current = checkNotNull(scenario) { "MainActivity not launched" }
        current.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    @Test
    fun systemBackFromSettingsAlarmEditAndStatsReturnsHome() {
        scenario = Screens.launchHome()
        compose.awaitText(str(R.string.app_name))

        compose.onNodeWithContentDescription(str(R.string.settings)).performClick()
        compose.awaitText(str(R.string.api_key))
        pressSystemBack()
        compose.awaitText(str(R.string.home_title))

        compose.onNodeWithContentDescription(str(R.string.add_alarm)).performClick()
        compose.awaitText(str(R.string.new_alarm))
        pressSystemBack()
        compose.awaitText(str(R.string.home_title))

        compose.onNodeWithContentDescription(str(R.string.stats_entry)).performClick()
        compose.awaitText(str(R.string.stats_title))
        pressSystemBack()
        compose.awaitText(str(R.string.home_title))
    }
}
