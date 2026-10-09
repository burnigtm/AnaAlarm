package com.anaalarm.ui

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.anaalarm.R
import com.anaalarm.support.*
import com.anaalarm.ui.wakeup.WakeUpActivity
import com.anaalarm.ui.wakeup.WAKE_STOP_TEST_TAG
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CompanionAccessibilityInstrumentedTest {
    @get:Rule val permissions = Screens.runtimePermissions()
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<WakeUpActivity>? = null
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private var originalScale = "1.0"

    @Before fun before() {
        TestEnv.resetAll()
        originalScale = shell("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null } ?: "1.0"
        shell("settings put system font_scale 1.5")
    }
    @After fun after() {
        scenario?.close()
        shell("settings put system font_scale $originalScale")
        AppLocales.apply("en", TestEnv.context)
        TestEnv.resetAll()
    }

    @Test fun englishLargeTextKeepsStopAndRecoveryMessageReachable() {
        AppLocales.apply("en", TestEnv.context)
        scenario = Screens.launchWakeUp()
        val english = TestEnv.context.createConfigurationContext(
            android.content.res.Configuration(TestEnv.context.resources.configuration).apply {
                setLocale(java.util.Locale.US)
            }
        )
        compose.awaitText(english.getString(R.string.no_api_key))
        scenario!!.onActivity { org.junit.Assert.assertEquals("en", it.resources.configuration.locales[0].language) }
        compose.onNodeWithTag(WAKE_STOP_TEST_TAG).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(english.getString(R.string.no_api_key)).performScrollTo().assertIsDisplayed()
        screenshot("companion-wake-large-en.png")
    }

    @Test fun portugueseLandscapeLargeTextKeepsStopReachable(): Unit = runBlocking {
        TestEnv.app.settingsStore.update(language = "pt")
        AppLocales.apply("pt", TestEnv.context)
        scenario = Screens.launchWakeUp()
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        val localized = TestEnv.context.createConfigurationContext(
            android.content.res.Configuration(TestEnv.context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag("pt-BR"))
            }
        )
        // Per-app UI locales are supported from API 33; older devices keep their system locale.
        val error = if (android.os.Build.VERSION.SDK_INT >= 33) localized.getString(R.string.no_api_key)
            else str(R.string.no_api_key)
        compose.awaitText(error)
        compose.onNodeWithTag(WAKE_STOP_TEST_TAG).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        screenshot("companion-wake-large-pt-landscape.png")
    }

    private fun screenshot(name: String) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(TestEnv.context.cacheDir, name))
    }
}
