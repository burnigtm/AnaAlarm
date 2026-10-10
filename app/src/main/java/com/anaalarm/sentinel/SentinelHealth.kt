package com.anaalarm.sentinel

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.anaalarm.AnaAlarmApp
import com.anaalarm.alarm.Notifications
import com.anaalarm.data.AnaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable internal enum class HealthStatus { READY, NEEDS_ATTENTION, UNAVAILABLE }
@Serializable internal enum class HealthCheck {
    EXACT_ALARMS, NOTIFICATIONS, ALARM_CHANNEL, SESSION_CHANNEL, FULL_SCREEN,
    BATTERY, MICROPHONE, SPEECH, TTS, API_KEY, ENABLED_ALARMS, NEXT_ALARM, PROCESS_EXITS
}
@Serializable internal enum class HealthDetail {
    PASSED, SETTING_REQUIRED, SERVICE_AVAILABLE, SERVICE_MISSING, KEY_MISSING,
    ALARM_COUNT, NEXT_TIME, OTHER_APP_NEXT, NO_NEXT_ALARM, LOCKED, CHECK_FAILED,
    EXIT_COUNT, UNSUPPORTED
}
@Serializable internal enum class SettingsTarget { APP, EXACT_ALARM, FULL_SCREEN, BATTERY, TTS, ANA_SETTINGS }
@Serializable internal data class HealthResult(
    val check: HealthCheck,
    val status: HealthStatus,
    val detail: HealthDetail,
    val value: Long? = null,
    val action: SettingsTarget? = null
)
@Serializable internal data class ProcessExit(val timestamp: Long, val reason: Int, val status: Int)
@Serializable internal data class HealthSnapshot(
    val results: List<HealthResult>,
    val exits: List<ProcessExit> = emptyList()
)
internal fun interface SentinelHealthChecker { suspend fun collect(): HealthSnapshot }

internal fun nextAlarmHealth(ownPackage: String, creatorPackage: String?, triggerTime: Long?): HealthResult =
    when {
        triggerTime == null -> HealthResult(HealthCheck.NEXT_ALARM, HealthStatus.UNAVAILABLE, HealthDetail.NO_NEXT_ALARM)
        creatorPackage != ownPackage -> HealthResult(HealthCheck.NEXT_ALARM, HealthStatus.UNAVAILABLE, HealthDetail.OTHER_APP_NEXT)
        else -> HealthResult(HealthCheck.NEXT_ALARM, HealthStatus.READY, HealthDetail.NEXT_TIME, triggerTime)
    }

internal class AndroidSentinelHealth(private val app: AnaAlarmApp) : SentinelHealthChecker {
    override suspend fun collect(): HealthSnapshot = withContext(Dispatchers.IO) {
        val results = mutableListOf<HealthResult>()
        var exits = emptyList<ProcessExit>()
        fun check(id: HealthCheck, block: () -> HealthResult) {
            results += runCatching(block).getOrElse {
                HealthResult(id, HealthStatus.UNAVAILABLE, HealthDetail.CHECK_FAILED)
            }
        }
        fun boolean(id: HealthCheck, target: SettingsTarget, available: Boolean, service: Boolean = false) =
            HealthResult(id, if (available) HealthStatus.READY else HealthStatus.NEEDS_ATTENTION,
                if (service) { if (available) HealthDetail.SERVICE_AVAILABLE else HealthDetail.SERVICE_MISSING }
                else if (available) HealthDetail.PASSED else HealthDetail.SETTING_REQUIRED,
                action = if (available) null else target)
        val notifications = app.getSystemService(NotificationManager::class.java)
        check(HealthCheck.EXACT_ALARMS) {
            boolean(HealthCheck.EXACT_ALARMS, SettingsTarget.EXACT_ALARM, app.alarmScheduler.canScheduleExact())
        }
        check(HealthCheck.NOTIFICATIONS) {
            boolean(HealthCheck.NOTIFICATIONS, SettingsTarget.APP, notifications.areNotificationsEnabled())
        }
        for ((id, channel, minimum) in listOf(
            Triple(HealthCheck.ALARM_CHANNEL, Notifications.CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH),
            Triple(HealthCheck.SESSION_CHANNEL, Notifications.SILENT_CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
        )) check(id) {
            boolean(id, SettingsTarget.APP, (notifications.getNotificationChannel(channel)?.importance ?: 0) >= minimum)
        }
        check(HealthCheck.FULL_SCREEN) {
            boolean(HealthCheck.FULL_SCREEN, SettingsTarget.FULL_SCREEN, Notifications.canUseFullScreenIntent(app))
        }
        check(HealthCheck.BATTERY) {
            val restricted = Build.VERSION.SDK_INT >= 28 && app.getSystemService(ActivityManager::class.java).isBackgroundRestricted
            boolean(HealthCheck.BATTERY, SettingsTarget.BATTERY,
                app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName) && !restricted)
        }
        check(HealthCheck.MICROPHONE) {
            boolean(HealthCheck.MICROPHONE, SettingsTarget.APP,
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        }
        check(HealthCheck.SPEECH) {
            boolean(HealthCheck.SPEECH, SettingsTarget.APP, SpeechRecognizer.isRecognitionAvailable(app), service = true)
        }
        check(HealthCheck.TTS) {
            boolean(HealthCheck.TTS, SettingsTarget.TTS,
                app.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty(), service = true)
        }
        if (app.isUserUnlocked()) {
            results += runCatching {
                app.ensureCredentialStorage()
                val present = app.settingsStore.settings.first().apiKey.isNotBlank()
                HealthResult(HealthCheck.API_KEY, if (present) HealthStatus.READY else HealthStatus.NEEDS_ATTENTION,
                    if (present) HealthDetail.PASSED else HealthDetail.KEY_MISSING,
                    action = if (present) null else SettingsTarget.ANA_SETTINGS)
            }.getOrElse { HealthResult(HealthCheck.API_KEY, HealthStatus.UNAVAILABLE, HealthDetail.CHECK_FAILED) }
            results += runCatching {
                val count = AnaDatabase.get(app).alarmDao().getEnabled().size.toLong()
                HealthResult(HealthCheck.ENABLED_ALARMS, HealthStatus.READY, HealthDetail.ALARM_COUNT, count)
            }.getOrElse { HealthResult(HealthCheck.ENABLED_ALARMS, HealthStatus.UNAVAILABLE, HealthDetail.CHECK_FAILED) }
        } else for (id in listOf(HealthCheck.API_KEY, HealthCheck.ENABLED_ALARMS)) {
            results += HealthResult(id, HealthStatus.UNAVAILABLE, HealthDetail.LOCKED)
        }
        check(HealthCheck.NEXT_ALARM) {
            val next = app.getSystemService(AlarmManager::class.java).nextAlarmClock
            nextAlarmHealth(app.packageName, next?.showIntent?.creatorPackage, next?.triggerTime)
        }
        check(HealthCheck.PROCESS_EXITS) {
            if (Build.VERSION.SDK_INT < 30) HealthResult(HealthCheck.PROCESS_EXITS, HealthStatus.UNAVAILABLE, HealthDetail.UNSUPPORTED)
            else {
                exits = app.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(app.packageName, 0, 20)
                    .filter { it.reason == ApplicationExitInfo.REASON_CRASH || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR }
                    .map { ProcessExit(it.timestamp, it.reason, it.status) }
                HealthResult(HealthCheck.PROCESS_EXITS, if (exits.isEmpty()) HealthStatus.READY else HealthStatus.NEEDS_ATTENTION,
                    HealthDetail.EXIT_COUNT, exits.size.toLong())
            }
        }
        HealthSnapshot(results, exits)
    }
}
