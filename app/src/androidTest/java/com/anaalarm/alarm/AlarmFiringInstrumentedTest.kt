package com.anaalarm.alarm

import android.content.Intent
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The end-to-end alarm path: broadcast → [AlarmReceiver] → [AlarmService] → full-screen
 * wake-up screen, with the alarm re-armed for the next day.
 *
 * The app is brought to the foreground first because Android 12+ only lets a foreground app
 * start a foreground service, which is how the receiver launches the session.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AlarmFiringInstrumentedTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        TestEnv.clearDatabase()
        TestEnv.resetSettings() // no API key: the session fails fast instead of calling DeepSeek
        assumeTrue(TestEnv.app.alarmScheduler.canScheduleExact())
    }

    @After
    fun tearDown() {
        dismissWakeUpScreen()
        scenario?.close()
        scenario = null
        TestEnv.clearDatabase()
    }

    @Test
    fun firedAlarmOpensTheWakeUpScreenAndReArmsTheAlarm() = runBlocking {
        val target = LocalDateTime.now().plusMinutes(12)
        val alarmId = TestEnv.app.memoryStore
            .upsertAlarm(0, target.hour, target.minute, 0, 10, true)
        val expectedReArm = TestEnv.app.alarmScheduler
            .nextFireTime(target.hour, target.minute, 0)
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
    fun stopButtonClosesTheSessionStartedByAnAlarm() = runBlocking {
        val alarmId = TestEnv.app.memoryStore.upsertAlarm(0, 6, 30, 0, 10, true)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.text(str(R.string.app_name))), 15_000)
        TestEnv.context.sendBroadcast(fireIntent(alarmId))
        assumeTrue(device.wait(Until.hasObject(By.text(str(R.string.wake_up_title))), 30_000))

        device.findObject(By.text(str(R.string.stop)))?.click()

        assertTrue(
            "wake-up screen stayed open after Stop",
            device.wait(Until.gone(By.text(str(R.string.wake_up_title))), 15_000)
        )
    }

    private fun fireIntent(alarmId: Long): Intent =
        Intent(AlarmReceiver.ACTION_FIRE_ALARM)
            .setClass(TestEnv.context, AlarmReceiver::class.java)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)

    private fun dismissWakeUpScreen() {
        repeat(3) {
            if (!device.hasObject(By.text(str(R.string.wake_up_title)))) return
            device.findObject(By.text(str(R.string.stop)))?.click()
            device.wait(Until.gone(By.text(str(R.string.wake_up_title))), 5_000)
        }
        device.pressHome()
    }
}
