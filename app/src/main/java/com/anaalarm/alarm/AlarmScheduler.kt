package com.anaalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.anaalarm.AnaAlarmApp
import com.anaalarm.data.AlarmEntity
import com.anaalarm.ui.wakeup.WakeUpActivity
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true

    fun nextFireTime(hour: Int, minute: Int, days: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime =
        AlarmTriggerCalculator.nextTriggerTime(hour, minute, days, now)

    fun schedule(alarm: AlarmEntity) {
        if (!alarm.enabled) {
            cancel(alarm)
            return
        }
        val triggerAt = nextFireTime(alarm.hour, alarm.minute, alarm.days)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        // setAlarmClock is the alarm-clock API: stronger idle exemptions and better
        // full-screen / lock-screen wake behavior than setExactAndAllowWhileIdle alone.
        val clockInfo = AlarmManager.AlarmClockInfo(triggerAt, showIntent(alarm.id))
        alarmManager.setAlarmClock(clockInfo, pendingIntent(alarm.id))
    }

    fun cancel(alarm: AlarmEntity) {
        alarmManager.cancel(pendingIntent(alarm.id))
        alarmManager.cancel(showIntent(alarm.id))
    }

    fun rescheduleNext(alarmId: Long) {
        val app = context.applicationContext as AnaAlarmApp
        app.applicationScope.launch {
            runCatching {
                app.memoryStore.getAlarm(alarmId)?.let { schedule(it) }
            }
        }
    }

    fun rescheduleAll() {
        val app = context.applicationContext as AnaAlarmApp
        app.applicationScope.launch {
            runCatching {
                val alarms = app.memoryStore.getEnabledAlarms()
                alarms.forEach { schedule(it) }
            }
        }
    }

    private fun pendingIntent(alarmId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE_ALARM)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Shown in the status-bar "next alarm" affordance; tapping opens the wake-up screen. */
    private fun showIntent(alarmId: Long): PendingIntent {
        val intent = Intent(context, WakeUpActivity::class.java)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            alarmId.toInt() + 50_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
