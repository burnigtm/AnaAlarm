package com.anaalarm.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

object Notifications {
    /** Bumped so devices pick up the stronger alarm channel attributes. */
    const val CHANNEL_ID = "alarm_channel_v2"

    /**
     * Silent twin of [CHANNEL_ID]. The wake-up screen talks to the user, so a ringtone would
     * both drown out the speech and leak into the microphone during recognition.
     */
    const val SILENT_CHANNEL_ID = "alarm_channel_silent"
    const val ALARM_NOTIFICATION_ID_BASE = 7000

    fun createChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Remove legacy channel that lacked alarm audio / bypass-DND settings.
        runCatching { manager.deleteNotificationChannel("alarm_channel") }

        createSilentChannel(manager)

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Wake-up alarm full-screen alerts"
            enableVibration(true)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(alarmUri, attrs)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createSilentChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(SILENT_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            SILENT_CHANNEL_ID,
            "Alarm session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing wake-up conversation"
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager?.canUseFullScreenIntent() == true
    }
}
