package com.anaalarm

import android.app.NotificationManager
import android.content.Context
import com.anaalarm.alarm.Notifications
import com.anaalarm.support.TestEnv
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest

/** Verifies the manual DI container in [AnaAlarmApp] wires a usable app on a real device. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class AnaAlarmAppInstrumentedTest {

    private val app get() = TestEnv.app

    @After
    fun tearDown() {
        app.overrideAiBackend(null)
    }

    @Test
    fun applicationUnderTestIsAnaAlarmApp() {
        assertEquals("com.anaalarm", TestEnv.context.packageName)
        assertSame(app, TestEnv.context.applicationContext)
    }

    @Test
    fun everyDependencyIsInitialised() {
        assertNotNull(app.settingsStore)
        assertNotNull(app.memoryStore)
        assertNotNull(app.deepSeekClient)
        assertNotNull(app.conversationEngine)
        assertNotNull(app.ttsManager)
        assertNotNull(app.speechListener)
        assertNotNull(app.alarmScheduler)
        assertTrue("applicationScope must outlive activities", app.applicationScope.isActive)
    }

    @Test
    fun startupCreatesAlarmAndSilentNotificationChannels() {
        val manager = TestEnv.context
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        assertNotNull(
            "alarm channel missing",
            manager.getNotificationChannel(Notifications.CHANNEL_ID)
        )
        assertNotNull(
            "silent session channel missing",
            manager.getNotificationChannel(Notifications.SILENT_CHANNEL_ID)
        )
        assertNull(
            "legacy channel should have been deleted on startup",
            manager.getNotificationChannel("alarm_channel")
        )
    }

    @Test
    fun recreateTtsSwapsInTheNewEngine() {
        val original = app.ttsManager
        app.recreateTts()
        assertNotSame(original, app.ttsManager)
    }

    @Test
    fun recreateSpeechSwapsInTheNewRecognizer() {
        val original = app.speechListener
        app.recreateSpeech()
        assertNotSame(original, app.speechListener)
    }

    @Test
    fun aiBackendOverrideIsReversible() {
        val real = app.deepSeekClient
        val realEngine = app.conversationEngine

        val fake = com.anaalarm.ai.DeepSeekClient(app.settingsStore)
        app.overrideAiBackend(fake)
        assertSame(fake, app.deepSeekClient)
        assertNotSame(realEngine, app.conversationEngine)

        app.overrideAiBackend(null)
        assertNotSame(fake, app.deepSeekClient)
        assertNotSame(real, app.deepSeekClient)
        assertNotNull(app.conversationEngine)
    }
}
