package com.anaalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anaalarm.AnaAlarmApp
import com.anaalarm.BuildConfig

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val ACTION_FIRE_ALARM = "com.anaalarm.action.FIRE_ALARM"
        private const val TAG = "AnaAlarm"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Debug-only explicit fire (adb); ignore in release builds.
        if (intent.action == ACTION_FIRE_ALARM && !BuildConfig.DEBUG) return

        val pending = goAsync()
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        Log.i(TAG, "AlarmReceiver fired alarmId=$alarmId action=${intent.action}")

        runCatching {
            AlarmService.start(context, alarmId)
        }.onFailure { err ->
            Log.e(TAG, "AlarmService.start failed", err)
            runCatching {
                context.startActivity(AlarmService.wakeUpIntent(context, alarmId))
            }.onFailure { e2 ->
                Log.e(TAG, "Direct WakeUpActivity start failed", e2)
            }
        }

        (context.applicationContext as? AnaAlarmApp)?.alarmScheduler?.rescheduleNext(alarmId)
        pending.finish()
    }
}
