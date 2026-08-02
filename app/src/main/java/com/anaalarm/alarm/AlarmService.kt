package com.anaalarm.alarm

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anaalarm.R
import com.anaalarm.ui.wakeup.WakeUpActivity

/**
 * Posts the full-screen alarm notification and brings [WakeUpActivity] over other apps.
 * Uses PendingIntent.send() with sender-side BAL options — startActivity() from a
 * background FGS is blocked on Android 14+.
 */
class AlarmService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L) ?: -1L
        val notificationId =
            Notifications.ALARM_NOTIFICATION_ID_BASE + alarmId.toInt().coerceAtLeast(0)

        val fullScreenPending = activityPendingIntent(alarmId.toInt(), alarmId)
        val contentPending = activityPendingIntent(alarmId.toInt() + 10_000, alarmId)

        // Start silent: the wake-up screen speaks, and a ringtone here would mask that
        // speech and bleed into the microphone while we listen for the user's reply.
        startAsForeground(notificationId, buildSessionNotification(this, contentPending))
        turnScreenOn()

        var launched = false
        runCatching {
            sendWakeUp(fullScreenPending)
            launched = true
            Log.i(TAG, "WakeUp PendingIntent.send() ok for alarmId=$alarmId")
        }.onFailure { err ->
            Log.w(TAG, "PendingIntent.send failed, trying startActivity", err)
            runCatching {
                startActivity(wakeUpIntent(this, alarmId))
                launched = true
            }.onFailure { e2 ->
                Log.e(TAG, "startActivity also failed — user must tap notification", e2)
            }
        }

        if (!launched) {
            // Nothing on screen to wake the user, so fall back to the ringing full-screen alert.
            runCatching {
                NotificationManagerCompat.from(this).notify(
                    notificationId,
                    buildAlarmNotification(this, fullScreenPending, contentPending)
                )
            }
        }

        android.os.Handler(mainLooper).postDelayed({
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
            Log.i(TAG, "AlarmService stopping launched=$launched alarmId=$alarmId")
        }, 3_000)

        return START_NOT_STICKY
    }

    private fun sendWakeUp(pending: PendingIntent) {
        val options: Bundle? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }.toBundle()
            } else null
        pending.send(this, 0, null, null, null, null, options)
    }

    private fun startAsForeground(notificationId: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground(specialUse) failed, falling back", e)
        }
        @Suppress("DEPRECATION")
        startForeground(notificationId, notification)
    }

    private fun activityPendingIntent(requestCode: Int, alarmId: Long): PendingIntent {
        val wakeIntent = wakeUpIntent(this, alarmId)
        return PendingIntent.getActivity(
            this,
            requestCode,
            wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun turnScreenOn() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "anaalarm:alarm"
        )
        runCatching {
            wakeLock.acquire(5_000)
            wakeLock.release()
        }
    }

    companion object {
        private const val TAG = "AnaAlarm"

        fun start(context: Context, alarmId: Long) {
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun wakeUpIntent(context: Context, alarmId: Long): Intent =
            Intent(context, WakeUpActivity::class.java)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )

        fun buildSessionNotification(
            context: Context,
            contentPending: PendingIntent
        ): Notification =
            NotificationCompat.Builder(context, Notifications.SILENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.wake_up_title))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(contentPending)
                .setSilent(true)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

        fun buildAlarmNotification(
            context: Context,
            fullScreenPending: PendingIntent,
            contentPending: PendingIntent
        ): Notification =
            NotificationCompat.Builder(context, Notifications.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.wake_up_title))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPending, true)
                .setContentIntent(contentPending)
                .setOngoing(true)
                .setAutoCancel(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }
}
