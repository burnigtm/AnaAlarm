package com.anaalarm.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.anaalarm.R
import com.anaalarm.support.Screens
import com.anaalarm.support.TestEnv
import com.anaalarm.support.awaitText
import com.anaalarm.support.str
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** The home recap card hosts a small idle buddy beside its title. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class HomeRecapBuddyInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = Screens.runtimePermissions()

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: androidx.test.core.app.ActivityScenario<com.anaalarm.MainActivity>? = null

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
        TestEnv.resetSettings()
    }

    private fun seedRecap() = runBlocking {
        TestEnv.app.memoryStore.saveDailyLog(
            "assistant: Good morning! You said you slept well.",
            LocalDate.now()
        )
    }

    @Test
    fun recapCardShowsTheBuddyBesideItsTitle() {
        seedRecap()
        scenario = Screens.launchHome()

        compose.awaitText(str(R.string.recap_title))

        compose.onNodeWithTag("home_recap_buddy").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.recap_title)).assertIsDisplayed()
    }

    @Test
    fun noRecapMeansNoBuddyNode() {
        // Without a summary and without habits the recap card is skipped entirely.
        scenario = Screens.launchHome()
        compose.awaitText(str(R.string.app_name))
        compose.awaitText(str(R.string.home_title))

        compose.onNodeWithTag("home_recap_buddy").assertDoesNotExist()
    }
}
