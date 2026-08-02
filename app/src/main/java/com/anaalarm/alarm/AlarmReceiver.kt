package com.anaalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anaalarm.AnaAlarmApp
import com.anaalarm.BuildConfig
import com.anaalarm.telemetry.LatencyMetrics
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_IS_SNOOZE = "extra_is_snooze"
        const val ACTION_SCHEDULED_ALARM = "com.anaalarm.action.SCHEDULED_ALARM"
        const val ACTION_DEBUG_FIRE_ALARM = "com.anaalarm.action.DEBUG_FIRE_ALARM"
        private const val TAG = "AnaAlarm"

        internal fun accepts(action: String?, debugBuild: Boolean): Boolean =
            action == ACTION_SCHEDULED_ALARM ||
                (debugBuild && action == ACTION_DEBUG_FIRE_ALARM)

        internal fun shouldHandleRegularScheduleAfterFire(isSnooze: Boolean): Boolean = !isSnooze

        internal fun shouldUseDirectBootState(userUnlocked: Boolean): Boolean = !userUnlocked
    }

    override fun onReceive(context: Context, intent: Intent) {
        // AlarmManager uses a private explicit action. Only debug APKs accept the exported
        // adb action declared in src/debug/AndroidManifest.xml.
        if (!accepts(intent.action, BuildConfig.DEBUG)) return

        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId < 0L) {
            Log.w(TAG, "Ignoring alarm broadcast without a valid alarm id")
            return
        }
        // Capture the AlarmManager delivery boundary before direct-boot retirement/re-arming so
        // delivery-to-audio telemetry includes every synchronous receiver step.
        val deliveredAtNanos = LatencyMetrics.nowNanos()
        Log.i(TAG, "AlarmReceiver fired alarmId=$alarmId action=${intent.action}")

        val app = context.applicationContext as? AnaAlarmApp ?: return
        val isSnooze = intent.getBooleanExtra(EXTRA_IS_SNOOZE, false)
        val directBoot = shouldUseDirectBootState(app.isUserUnlocked())

        if (directBoot && shouldHandleRegularScheduleAfterFire(isSnooze)) {
            // Retire/re-arm before launching UI so a pre-unlock one-shot cannot be resurrected by
            // another reboot. This path reads only the device-protected minimal mirror.
            runCatching { app.alarmScheduler.handleDirectBootFiredAlarm(alarmId) }
                .onSuccess {
                    Log.i(TAG, "Direct-boot post-fire alarmId=$alarmId result=$it")
                }
                .onFailure {
                    Log.e(TAG, "Direct-boot post-fire handling failed alarmId=$alarmId", it)
                }
        }

        runCatching {
            AlarmService.start(context, alarmId, deliveredAtNanos)
        }.onFailure { err ->
            Log.e(TAG, "AlarmService.start failed", err)
            runCatching {
                context.startActivity(AlarmService.wakeUpIntent(context, alarmId))
            }.onFailure { e2 ->
                Log.e(TAG, "Direct WakeUpActivity start failed", e2)
            }
        }

        // A snooze is a separate one-shot PendingIntent. It must not disable the stored
        // one-shot again or re-arm the regular repeat schedule when it fires.
        if (!shouldHandleRegularScheduleAfterFire(isSnooze)) {
            Log.i(TAG, "Snooze fired alarmId=$alarmId; regular post-fire handling skipped")
            return
        }

        if (directBoot) return

        val pending = goAsync()
        app.applicationScope.launch {
            try {
                check(app.ensureCredentialStorage()) {
                    "Credential storage unavailable for an unlocked alarm"
                }
                val result = app.alarmScheduler.handleFiredAlarm(alarmId)
                Log.i(TAG, "Alarm post-fire handling alarmId=$alarmId result=$result")
            } catch (error: Exception) {
                Log.e(TAG, "Alarm post-fire handling failed alarmId=$alarmId", error)
            } finally {
                // Keep the receiver process lifetime valid until Room and AlarmManager have
                // committed the one-shot disable or repeating re-arm.
                pending.finish()
            }
        }
    }
}
