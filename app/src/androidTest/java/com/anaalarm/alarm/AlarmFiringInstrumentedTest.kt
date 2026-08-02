package com.anaalarm.alarm

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.anaalarm.MainActivity
import com.anaalarm.R
import com.anaalarm.support.TestEnv
import com.anaalarm.support.str
import com.anaalarm.ui.wakeup.WAKE_SNOOZE_TEST_TAG
import com.anaalarm.ui.wakeup.WAKE_STOP_TEST_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The end-to-end alarm path: broadcast → [AlarmReceiver] → [AlarmService] → full-screen
 * wake-up screen, with repeating alarms re-armed and one-shot alarms disabled.
 *
 * The app is brought to the foreground first because Android 12+ only lets a foreground app
 * start a foreground service, which is how the receiver launches the session.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AlarmFiringInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        TestEnv.clearDatabase()
        TestEnv.resetSettings() // no API key: the session fails fast instead of calling DeepSeek
        assertTrue(
            "SCHEDULE_EXACT_ALARM must be granted for the required firing suite",
            TestEnv.app.alarmScheduler.canScheduleExact()
        )
    }

    @After
    fun tearDown() {
        AlarmService.stop(TestEnv.context)
        dismissWakeUpScreen()
        scenario?.close()
        scenario = null
        TestEnv.clearDatabase()
    }

    @Test
    fun firedAlarmOpensTheWakeUpScreenAndReArmsTheAlarm() = runBlocking {
        val target = LocalDateTime.now().plusMinutes(12)
        val alarmId = TestEnv.app.memoryStore
            .upsertAlarm(0, target.hour, target.minute, 0b1111111, 10, true)
        val expectedReArm = TestEnv.app.alarmScheduler
            .nextFireTime(target.hour, target.minute, 0b1111111)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.text(str(R.string.app_name))), 15_000)

        TestEnv.context.sendBroadcast(fireIntent(alarmId))

        assertTrue(
            "wake-up screen did not appear after the alarm fired",
            device.wait(Until.hasObject(By.text(str(R.string.wake_up_title))), 30_000)
        )
        assertTrue(
            "alarm was not re-armed for the next occurrence",
            TestEnv.waitUntil(timeoutMs = 15_000) {
                TestEnv.alarmManager.nextAlarmClock?.triggerTime == expectedReArm
            }
        )
    }

    @Test
    fun firedOneShotAlarmIsDisabledInsteadOfReArmed() = runBlocking {
        val alarmId = TestEnv.app.memoryStore.upsertAlarm(0, 6, 30, 0, 10, true)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.text(str(R.string.app_name))), 15_000)
        TestEnv.context.sendBroadcast(fireIntent(alarmId))

        assertTrue(
            "wake-up screen did not appear for the one-shot alarm",
            device.wait(Until.hasObject(By.text(str(R.string.wake_up_title))), 30_000)
        )
        assertTrue(
            "one-shot alarm remained enabled after delivery",
            TestEnv.waitUntil(timeoutMs = 15_000) {
                runBlocking { TestEnv.app.memoryStore.getAlarm(alarmId)?.enabled } == false
            }
        )
    }

    @Test
    fun firedSnoozeDoesNotMutateTheRegularAlarmSchedule() = runBlocking {
        val alarmId = TestEnv.app.memoryStore.upsertAlarm(0, 6, 45, 0, 10, true)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.text(str(R.string.app_name))), 15_000)
        TestEnv.context.sendBroadcast(fireIntent(alarmId, isSnooze = true))
        assertTrue(
            device.wait(Until.hasObject(By.text(str(R.string.wake_up_title))), 30_000)
        )

        Thread.sleep(1_500)
        assertEquals(true, TestEnv.app.memoryStore.getAlarm(alarmId)?.enabled)
    }

    @Test
    fun aiFailureKeepsFallbackRingingUntilStopClosesTheAlarm() = runBlocking {
        val alarmId = TestEnv.app.memoryStore.upsertAlarm(0, 6, 30, 0, 10, true)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.text(str(R.string.app_name))), 15_000)
        TestEnv.context.sendBroadcast(fireIntent(alarmId))
        assertTrue(
            "wake-up screen did not appear before the fallback assertion",
            device.wait(Until.hasObject(By.text(str(R.string.wake_up_title))), 30_000)
        )
        assertTrue(
            "no-key failure did not become visible",
            waitForVisibleComposeText(str(R.string.no_api_key))
        )
        assertTrue(
            "AI failure silenced the local alarm before explicit user action",
            AlarmService.isFallbackActiveForTest()
        )

        // Query the app's Compose tree so a heads-up notification action with the same label
        // cannot satisfy the assertion or receive the click.
        performWakeAction(WAKE_STOP_TEST_TAG)

        assertTrue(
            "wake-up screen stayed open after Stop",
            waitForWakeActionToDisappear(WAKE_STOP_TEST_TAG)
        )
        assertTrue(
            "fallback service remained active after explicit Stop",
            TestEnv.waitUntil(timeoutMs = 10_000) {
                !AlarmService.isFallbackActiveForTest()
            }
        )
    }

    @Test
    fun snoozeStillWorksAfterAiFailureAndStopsTheFallback() = runBlocking {
        val alarmId = TestEnv.app.memoryStore.upsertAlarm(0, 6, 30, 0, 7, true)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.text(str(R.string.app_name))), 15_000)
        TestEnv.context.sendBroadcast(fireIntent(alarmId))
        assertTrue(
            "no-key failure did not become visible before Snooze",
            waitForVisibleComposeText(str(R.string.no_api_key))
        )

        val earliest = System.currentTimeMillis() + 6 * 60_000L
        val latest = System.currentTimeMillis() + 8 * 60_000L
        performWakeAction(WAKE_SNOOZE_TEST_TAG)

        assertTrue(
            "Snooze did not close the failed wake session",
            waitForWakeActionToDisappear(WAKE_SNOOZE_TEST_TAG)
        )
        assertTrue(
            "Snooze did not register the configured exact alarm",
            TestEnv.waitUntil(timeoutMs = 10_000) {
                TestEnv.alarmManager.nextAlarmClock?.triggerTime?.let { it in earliest..latest } == true
            }
        )
        assertTrue(
            "Snooze left the fallback alarm active",
            TestEnv.waitUntil { !AlarmService.isFallbackActiveForTest() }
        )
    }

    private fun fireIntent(alarmId: Long, isSnooze: Boolean = false): Intent =
        Intent(AlarmReceiver.ACTION_DEBUG_FIRE_ALARM)
            .setClass(TestEnv.context, AlarmReceiver::class.java)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            .putExtra(AlarmReceiver.EXTRA_IS_SNOOZE, isSnooze)

    private fun waitForVisibleComposeText(text: String): Boolean =
        TestEnv.waitUntil(timeoutMs = 30_000) {
            runCatching {
                compose.onNodeWithText(text).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }

    private fun performWakeAction(tag: String) {
        compose.onNodeWithTag(tag)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitForWakeActionToDisappear(tag: String): Boolean =
        TestEnv.waitUntil(timeoutMs = 15_000) {
            wakeActionIsAbsent(tag)
        }

    private fun wakeActionIsAbsent(tag: String): Boolean =
        try {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        } catch (exception: IllegalStateException) {
            // Once finish() removes the Activity's composition, Compose has no hierarchy to
            // query. That is the strongest possible evidence that this in-app action is gone.
            if (exception.message?.contains("No compose hierarchies found") == true) {
                true
            } else {
                throw exception
            }
        }

    private fun dismissWakeUpScreen() {
        repeat(3) {
            if (wakeActionIsAbsent(WAKE_STOP_TEST_TAG)) return
            runCatching { performWakeAction(WAKE_STOP_TEST_TAG) }
            if (waitForWakeActionToDisappear(WAKE_STOP_TEST_TAG)) return
        }
        device.pressHome()
    }
}
