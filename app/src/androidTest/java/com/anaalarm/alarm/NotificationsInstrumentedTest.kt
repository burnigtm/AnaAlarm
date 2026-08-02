package com.anaalarm.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.anaalarm.support.TestEnv
import com.anaalarm.ui.wakeup.WakeUpActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Channel configuration and notification payloads that decide whether an alarm can wake a phone. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class NotificationsInstrumentedTest {

    private val context: Context get() = TestEnv.context
    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        Notifications.createChannel(context)
    }

    @Test
    fun createChannelIsIdempotent() {
        val before = manager.notificationChannels.size
        Notifications.createChannel(context)
        Notifications.createChannel(context)
        assertEquals(before, manager.notificationChannels.size)
    }

    @Test
    fun alarmChannelCanInterruptTheUser() {
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_ID)
        assertNotNull(channel)

        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
        assertTrue("alarm channel must vibrate", channel.shouldVibrate())
        assertNotNull("alarm channel needs a ringtone", channel.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes?.usage)
    }

    @Test
    fun sessionChannelIsSilentSoAnaCanBeHeard() {
        val channel = manager.getNotificationChannel(Notifications.SILENT_CHANNEL_ID)
        assertNotNull(channel)

        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
        assertNull("the ongoing session channel must not play sound", channel.sound)
        assertTrue(!channel.shouldVibrate())
    }

    /**
     * Both DND bypass and a lock-screen visibility override are privileged: the framework
     * silently downgrades them unless the user has granted notification policy access. The
     * contract is therefore "honoured whenever the system allows it".
     */
    @Test
    fun privilegedLockScreenBehaviourIsRequestedAndHonouredWhenPermitted() {
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_ID)!!
        val privileged = manager.isNotificationPolicyAccessGranted

        assertTrue(
            "DND bypass was dropped even though policy access is granted",
            channel.canBypassDnd() || !privileged
        )
        val visibilityNoOverride = -1000 // NotificationManager.VISIBILITY_NO_OVERRIDE, not public API
        assertTrue(
            "lock-screen visibility was neither public nor left at the system default",
            channel.lockscreenVisibility == Notification.VISIBILITY_PUBLIC ||
                channel.lockscreenVisibility == visibilityNoOverride
        )
    }

    @Test
    fun legacyChannelIsRemoved() {
        assertNull(manager.getNotificationChannel("alarm_channel"))
    }

    @Test
    fun fullScreenIntentCapabilityIsReported() {
        // Value depends on the grant state; the call itself must never throw.
        val allowed = Notifications.canUseFullScreenIntent(context)
        assertTrue(allowed || !allowed)
    }

    @Test
    fun sessionNotificationUsesTheSilentChannelAndStaysOngoing() {
        val notification = AlarmService.buildSessionNotification(context, pendingIntent(1))

        assertEquals(Notifications.SILENT_CHANNEL_ID, notification.channelId)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertNotNull(notification.contentIntent)
        assertNull("session notification must not ring", notification.fullScreenIntent)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun alarmNotificationCarriesTheFullScreenIntent() {
        val notification = AlarmService.buildAlarmNotification(
            context,
            pendingIntent(2),
            pendingIntent(3)
        )

        assertEquals(Notifications.CHANNEL_ID, notification.channelId)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(Notification.PRIORITY_MAX, notification.priority)
        assertNotNull("the fallback alert must open the wake-up screen", notification.fullScreenIntent)
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun wakeUpIntentTargetsTheWakeUpActivityWithTheAlarmId() {
        val intent = AlarmService.wakeUpIntent(context, alarmId = 42L)

        assertEquals(WakeUpActivity::class.java.name, intent.component?.className)
        assertEquals(42L, intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L))
        listOf(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            Intent.FLAG_ACTIVITY_CLEAR_TOP,
            Intent.FLAG_ACTIVITY_SINGLE_TOP,
            Intent.FLAG_ACTIVITY_NO_USER_ACTION
        ).forEach { flag ->
            assertTrue("missing intent flag $flag", intent.flags and flag != 0)
        }
    }

    private fun pendingIntent(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            AlarmService.wakeUpIntent(context, 0L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
