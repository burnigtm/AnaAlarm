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
            Manifest.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED
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
    fun microphoneHardwareIsOptionalBecauseTypingIsSupported() {
        val microphone = packageInfo(PackageManager.GET_CONFIGURATIONS)
            .reqFeatures
            ?.firstOrNull { it.name == PackageManager.FEATURE_MICROPHONE }
        assertNotNull("microphone feature declaration missing", microphone)
        assertEquals(0, microphone!!.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
    }

    @Test
    fun wakeUpActivityIsPrivateAdaptiveAndShowsOverTheLockScreen() {
        val activity = packageInfo(PackageManager.GET_ACTIVITIES)
            .activities
            ?.first { it.name == "com.anaalarm.ui.wakeup.WakeUpActivity" }
        assertNotNull(activity)

        assertFalse("wake-up screen must not be exported", activity!!.exported)
        assertTrue("wake-up screen must run before first unlock", activity.directBootAware)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, activity.launchMode)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.screenOrientation)
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
    fun alarmServiceIsRegisteredForExactAlarmContinuation() {
        val service = packageInfo(PackageManager.GET_SERVICES)
            .services
            ?.first { it.name == "com.anaalarm.alarm.AlarmService" }
        assertNotNull(service)
        assertFalse(service!!.exported)
        assertTrue("alarm service must run before first unlock", service.directBootAware)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                service.foregroundServiceType
            )
        }
    }

    @Test
    fun bothReceiversAreRegistered() {
        val receivers = packageInfo(PackageManager.GET_RECEIVERS)
            .receivers
            .orEmpty()

        val receiverNames = receivers.map { it.name }

        assertTrue(receiverNames.contains("com.anaalarm.alarm.AlarmReceiver"))
        assertTrue(receiverNames.contains("com.anaalarm.alarm.BootReceiver"))
        receivers
            .filter {
                it.name == "com.anaalarm.alarm.AlarmReceiver" ||
                    it.name == "com.anaalarm.alarm.BootReceiver"
            }
            .forEach {
                assertTrue("${it.name} must be direct-boot aware", it.directBootAware)
            }
    }

    @Test
    fun debugAlarmReceiverResolvesOnlyTheManualTestAction() {
        val intent = Intent(AlarmReceiver.ACTION_DEBUG_FIRE_ALARM).setPackage(packageName)
        @Suppress("DEPRECATION")
        val matches = pm.queryBroadcastReceivers(intent, 0)
        assertTrue(
            "debug manual-fire action must reach AlarmReceiver in the debug APK",
            matches.any { it.activityInfo.name == "com.anaalarm.alarm.AlarmReceiver" }
        )
    }

    @Test
    fun applicationClassAndSdkLevelsMatchTheProjectContract() {
        val info = packageInfo(0).applicationInfo!!
        assertEquals("com.anaalarm.AnaAlarmApp", info.className)
        assertEquals(26, info.minSdkVersion)
        assertEquals(36, info.targetSdkVersion)
    }
}
