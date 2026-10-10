package com.anaalarm.sentinel

import android.app.NotificationManager
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anaalarm.MainActivity
import com.anaalarm.R
import com.anaalarm.alarm.Notifications
import com.anaalarm.support.Screens
import com.anaalarm.support.TestEnv
import com.anaalarm.support.awaitText
import com.anaalarm.support.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SentinelInstrumentedTest {
    @get:Rule val permissions = Screens.runtimePermissions()
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun prepare() = runBlocking {
        TestEnv.clearDatabase()
        TestEnv.resetSettings()
        TestEnv.app.sentinelHistory!!.clear()
    }
    @After fun cleanup() {
        scenario?.close()
        Notifications.createChannel(TestEnv.context)
        runBlocking { TestEnv.app.sentinelHistory!!.clear() }
    }

    private fun open() {
        scenario = Screens.launchHome()
        compose.onNodeWithContentDescription(str(R.string.settings)).performClick()
        compose.awaitText(str(R.string.api_key))
        compose.onNodeWithTag("open-sentinel").performScrollTo().performClick()
        compose.awaitText(str(R.string.sentinel_intro))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("sentinel-SESSION_CHANNEL").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @Test fun settingsNavigationAndBackReturnToSettings() {
        open()
        compose.onNodeWithTag("sentinel-EXACT_ALARMS").assertExists()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("open-sentinel").assertExists()
    }

    @Test fun refreshReflectsChangedNotificationChannel() {
        open()
        val manager = TestEnv.context.getSystemService(NotificationManager::class.java)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        manager.deleteNotificationChannel(Notifications.SILENT_CHANNEL_ID)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(str(R.string.sentinel_attention)) and hasAnyAncestor(hasTestTag("sentinel-SESSION_CHANNEL")))
                .fetchSemanticsNodes().isNotEmpty()
        }
        Notifications.createChannel(TestEnv.context)
        compose.onNodeWithTag("sentinel-refresh").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(str(R.string.sentinel_ready)) and hasAnyAncestor(hasTestTag("sentinel-SESSION_CHANNEL")))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun clearHistoryRemovesPersistedEvents() = runBlocking {
        TestEnv.app.sentinelHistory!!.record(EventCode.ALARM_PLAYBACK_FAILED, EventOutcome.FAILED, null)
        assertTrue(TestEnv.app.sentinelHistory!!.snapshot().isNotEmpty())
        open()
        compose.onNodeWithTag("sentinel-clear").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(str(R.string.sentinel_no_events)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(TestEnv.app.sentinelHistory!!.snapshot().isEmpty())
        val disk = File(TestEnv.context.filesDir, "sentinel/events.json").readText()
        assertEquals(emptyList<SentinelEvent>(), Json.decodeFromString<List<SentinelEvent>>(disk))
    }

    @Test fun reportShareUsesNarrowReadOnlyContentUri() = runBlocking {
        val context = TestEnv.context
        val report = SentinelReport(health = AndroidSentinelHealth(TestEnv.app).collect(), events = emptyList())
        val intent = AndroidSentinelReportExporter(context).shareIntent(report)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/json", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        val uri = intent.clipData!!.getItemAt(0).uri
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.sentinel.files", uri.authority)
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals(report, Json.decodeFromString<SentinelReport>(text))
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertFalse(text.contains("apiKey"))
        assertFalse(text.contains("transcript"))
        val privateFile = File(context.filesDir, "sentinel-private-test.txt").apply { writeText("private") }
        try {
            val result = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.sentinel.files", privateFile) }
            assertTrue("Provider must reject files outside report cache", result.isFailure)
        } finally { privateFile.delete() }
    }

    @Test fun sentinelResourcesResolveInBothLanguages() {
        fun localized(locale: Locale) = TestEnv.context.createConfigurationContext(
            Configuration(TestEnv.context.resources.configuration).apply { setLocale(locale) })
        val en = localized(Locale.US)
        val pt = localized(Locale.forLanguageTag("pt-BR"))
        assertEquals("Needs attention", en.getString(R.string.sentinel_attention))
        assertEquals("Precisa de atenção", pt.getString(R.string.sentinel_attention))
        assertTrue(pt.getString(R.string.sentinel_alarm_count, 2).contains("2"))
        assertFalse(en.getString(R.string.sentinel_privacy).isBlank())
    }
}
