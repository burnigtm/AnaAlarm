package com.anaalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.anaalarm.MainActivity
import com.anaalarm.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Home-screen widget showing the system's next scheduled alarm clock entry.
 *
 * Framework RemoteViews only — no extra dependency. Refreshed on the half-hourly system tick,
 * every provider update, and explicitly from [AlarmScheduler] whenever a schedule changes, so
 * the widget never claims an alarm that is not actually registered.
 */
class NextAlarmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context, manager, appWidgetIds)
    }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("EEE HH:mm")

        /** Pushes current state to every placed widget; safe from any process state. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, NextAlarmWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) refresh(context, manager, ids)
        }

        private fun refresh(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val next = alarmManager?.nextAlarmClock
            val views = RemoteViews(context.packageName, R.layout.next_alarm_widget)

            if (next != null) {
                views.setTextViewText(
                    R.id.widget_label,
                    context.getString(R.string.widget_next_alarm)
                )
                views.setTextViewText(
                    R.id.widget_time,
                    TIME_FORMAT.format(Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault()))
                )
            } else {
                views.setTextViewText(
                    R.id.widget_label,
                    context.getString(R.string.widget_no_alarm)
                )
                views.setTextViewText(R.id.widget_time, "")
            }
            views.setOnClickPendingIntent(R.id.widget_root, tapPendingIntent(context))

            appWidgetIds.forEach { appWidgetId ->
                runCatching { manager.updateAppWidget(appWidgetId, views) }
            }
        }

        private fun tapPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
