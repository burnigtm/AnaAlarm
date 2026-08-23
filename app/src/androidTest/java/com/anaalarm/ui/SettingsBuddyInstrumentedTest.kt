package com.anaalarm.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "wake-up buddy" picker in Settings: live-animated species cards with selection state,
 * persisted through the regular Save flow and remembered across visits.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SettingsBuddyInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = Screens.runtimePermissions()

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null

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

    private fun openSettings() {
        if (scenario == null) scenario = Screens.launchHome()
        compose.awaitText(str(R.string.app_name))
        compose.onNodeWithContentDescription(str(R.string.settings)).performClick()
        compose.awaitText(str(R.string.api_key))
    }

    private fun back() {
        compose.onNodeWithContentDescription(str(R.string.back)).performClick()
        compose.awaitText(str(R.string.home_title))
    }

    private fun scrollToBuddySection() {
        compose.onNodeWithText(str(R.string.buddy_label)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pickerShowsAllThreeSpeciesWithCheetahSelectedByDefault() {
        openSettings()
        scrollToBuddySection()

        compose.onNodeWithTag("buddy_card_cheetah").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("buddy_card_dino").performScrollTo().assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag("buddy_card_zebra").performScrollTo().assertIsDisplayed().assertIsNotSelected()
    }

    @Test
    fun previewsKeepAnimatingWithoutDisruptingSelection() {
        openSettings()
        scrollToBuddySection()

        compose.onNodeWithTag("buddy_card_cheetah").assertIsSelected()
        compose.waitForIdle()
        compose.onNodeWithTag("buddy_card_cheetah").assertIsSelected()
    }

    @Test
    fun pickingTheDinoAndSavingPersistsTheChoice() {
        openSettings()
        scrollToBuddySection()
        compose.onNodeWithTag("buddy_card_dino").performScrollTo().performClick()

        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()
        compose.awaitText(str(R.string.settings_saved))

        assertEquals("dino", TestEnv.settings().avatar)
    }

    @Test
    fun buddyChoiceIsRememberedAcrossVisits() {
        openSettings()
        scrollToBuddySection()
        compose.onNodeWithTag("buddy_card_zebra").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()
        compose.awaitText(str(R.string.settings_saved))

        back()
        openSettings()
        scrollToBuddySection()

        compose.onNodeWithTag("buddy_card_zebra").assertIsSelected()
        compose.onNodeWithTag("buddy_card_cheetah").performScrollTo().assertIsNotSelected()
        assertEquals("zebra", TestEnv.settings().avatar)
    }
}
