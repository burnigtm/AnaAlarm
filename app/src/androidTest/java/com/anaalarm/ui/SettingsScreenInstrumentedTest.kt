package com.anaalarm.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings screen: everything Ana needs to know about the user, persisted to DataStore. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SettingsScreenInstrumentedTest {

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

    @Test
    fun freshInstallShowsDefaults() {
        openSettings()

        compose.onNodeWithText(str(R.string.api_key_hint)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.habits_hint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.interests_hint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("10 min").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.language_en)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(str(R.string.language_pt)).performScrollTo().assertIsNotSelected()
    }

    @Test
    fun savingWritesEveryFieldToDataStore() {
        openSettings()

        compose.onNodeWithText(str(R.string.api_key_hint)).performTextReplacement("sk-from-ui")
        compose.onNodeWithText("Ana").performTextReplacement("Marina")
        compose.onNodeWithText(str(R.string.habits_hint))
            .performScrollTo()
            .performTextReplacement("water the plants, make coffee")
        compose.onNodeWithText(str(R.string.interests_hint))
            .performScrollTo()
            .performTextReplacement("photography, books")
        compose.onNodeWithText(str(R.string.language_pt)).performScrollTo().performClick()

        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()
        compose.awaitText(str(R.string.settings_saved))

        val settings = TestEnv.settings()
        assertEquals("sk-from-ui", settings.apiKey)
        assertEquals("Marina", settings.name)
        assertEquals("pt", settings.language)
        assertEquals(listOf("water the plants", "make coffee"), settings.habits)
        assertEquals(listOf("photography", "books"), settings.interests)
    }

    @Test
    fun savedValuesArePrefilledWhenSettingsIsReopened() {
        openSettings()
        compose.onNodeWithText("Ana").performTextReplacement("InstrumentedUser")
        compose.onNodeWithText(str(R.string.habits_hint))
            .performScrollTo()
            .performTextReplacement("stretch")
        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()
        compose.awaitText(str(R.string.settings_saved))

        back()
        openSettings()

        compose.awaitText("InstrumentedUser")
        compose.onNodeWithText("InstrumentedUser").assertIsDisplayed()
        compose.onNodeWithText("stretch").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun languageChoiceIsRememberedAcrossVisits() {
        openSettings()
        compose.onNodeWithText(str(R.string.language_pt)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()
        compose.awaitText(str(R.string.settings_saved))

        back()
        openSettings()

        compose.onNodeWithText(str(R.string.language_pt)).assertIsSelected()
        compose.onNodeWithText(str(R.string.language_en)).assertIsNotSelected()
        assertEquals("pt", TestEnv.settings().language)
    }

    @Test
    fun leavingWithoutSavingKeepsThePreviousConfiguration() = runBlocking {
        TestEnv.app.settingsStore.update(name = "Kept")
        openSettings()

        compose.onNodeWithText("Kept").performTextReplacement("Discarded")
        back()

        assertEquals("Kept", TestEnv.settings().name)
    }

    @Test
    fun apiKeyIsSanitisedBeforeItIsStored() {
        openSettings()

        compose.onNodeWithText(str(R.string.api_key_hint)).performTextReplacement("  sk-padded  ")
        compose.onNodeWithText(str(R.string.save)).performScrollTo().performClick()
        compose.awaitText(str(R.string.settings_saved))

        assertEquals("sk-padded", TestEnv.settings().apiKey)
    }

    @Test
    fun sessionLengthSectionIsPresentWithTheStoredValue() = runBlocking {
        TestEnv.app.settingsStore.update(sessionMinutes = 13)
        openSettings()

        compose.onNodeWithText(str(R.string.session_minutes)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("13 min").performScrollTo().assertIsDisplayed()
        assertTrue(TestEnv.settings().sessionMinutes == 13)
    }
}
