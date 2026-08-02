package com.anaalarm

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.anaalarm.alarm.AlarmReceiver
import com.anaalarm.support.TestEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Asserts the shipped manifest of the *installed* APK. These guard the declarations an alarm
 * app cannot work without: exact alarms, full-screen intents, boot re-scheduling and a
 * lock-screen activity.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class ManifestInstrumentedTest {

    private val packageName = "com.anaalarm"
    private val pm: PackageManager get() = TestEnv.context.packageManager

    @Suppress("DEPRECATION")
    private fun packageInfo(flags: Int): PackageInfo = pm.getPackageInfo(packageName, flags)

    @Test
    fun declaresEveryPermissionTheAlarmFlowNeeds() {
        val declared = packageInfo(PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()

        val required = listOf(
            Manifest.permission.INTERNET,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SCHEDULE_EXACT_ALARM,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.USE_FULL_SCREEN_INTENT,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.VIBRATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE
        )
        required.forEach { permission ->
            assertTrue("missing permission $permission", declared.contains(permission))
        }
    }

    @Test
    fun mainActivityIsTheLauncherEntryPoint() {
        val launch = pm.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher activity", launch)
        assertEquals(MainActivity::class.java.name, launch!!.component?.className)
    }

    @Test
    fun wakeUpActivityIsPrivatePortraitAndShowsOverTheLockScreen() {
        val activity = packageInfo(PackageManager.GET_ACTIVITIES)
            .activities
            ?.first { it.name == "com.anaalarm.ui.wakeup.WakeUpActivity" }
        assertNotNull(activity)

        assertFalse("wake-up screen must not be exported", activity!!.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, activity.launchMode)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.screenOrientation)
        assertTrue(
            "the wake-up screen must not linger in recents",
            activity.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0
        )
        assertTrue(
            "an empty task affinity keeps the session off the main task",
            activity.taskAffinity.isNullOrEmpty()
        )
    }

    @Test
    fun alarmServiceIsRegisteredAsSpecialUseForegroundService() {
        val service = packageInfo(PackageManager.GET_SERVICES)
            .services
            ?.first { it.name == "com.anaalarm.alarm.AlarmService" }
        assertNotNull(service)
        assertFalse(service!!.exported)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                service.foregroundServiceType
            )
        }
    }

    @Test
    fun bothReceiversAreRegistered() {
        val receivers = packageInfo(PackageManager.GET_RECEIVERS)
            .receivers
            ?.map { it.name }
            .orEmpty()

        assertTrue(receivers.contains("com.anaalarm.alarm.AlarmReceiver"))
        assertTrue(receivers.contains("com.anaalarm.alarm.BootReceiver"))
    }

    @Test
    fun alarmReceiverResolvesTheFireAlarmAction() {
        val intent = Intent(AlarmReceiver.ACTION_FIRE_ALARM).setPackage(packageName)
        @Suppress("DEPRECATION")
        val matches = pm.queryBroadcastReceivers(intent, 0)
        assertTrue(
            "FIRE_ALARM must reach AlarmReceiver",
            matches.any { it.activityInfo.name == "com.anaalarm.alarm.AlarmReceiver" }
        )
    }

    @Test
    fun applicationClassAndSdkLevelsMatchTheProjectContract() {
        val info = packageInfo(0).applicationInfo!!
        assertEquals("com.anaalarm.AnaAlarmApp", info.className)
        assertEquals(26, info.minSdkVersion)
        assertEquals(35, info.targetSdkVersion)
    }
}
