package com.anaalarm.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.anaalarm.MainActivity
import com.anaalarm.R
import com.anaalarm.support.Screens
import com.anaalarm.support.TestEnv
import com.anaalarm.support.awaitText
import com.anaalarm.support.str
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System Back from nested screens must return to Home (not finish MainActivity).
 * Fails if AppRoot lacks BackHandler.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AppRootBackNavigationInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = Screens.runtimePermissions()

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

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

    @Test
    fun systemBackFromSettingsAlarmEditAndStatsReturnsHome() {
        scenario = Screens.launchHome()
        compose.awaitText(str(R.string.app_name))

        compose.onNodeWithContentDescription(str(R.string.settings)).performClick()
        compose.awaitText(str(R.string.api_key))
        assertTrue(device.pressBack())
        compose.awaitText(str(R.string.home_title))

        compose.onNodeWithContentDescription(str(R.string.add_alarm)).performClick()
        compose.awaitText(str(R.string.new_alarm))
        assertTrue(device.pressBack())
        compose.awaitText(str(R.string.home_title))

        compose.onNodeWithContentDescription(str(R.string.stats_entry)).performClick()
        compose.awaitText(str(R.string.stats_title))
        assertTrue(device.pressBack())
        compose.awaitText(str(R.string.home_title))
    }
}
