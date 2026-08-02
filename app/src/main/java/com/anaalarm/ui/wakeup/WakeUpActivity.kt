package com.anaalarm.ui.wakeup

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anaalarm.AnaAlarmApp
import com.anaalarm.alarm.AlarmReceiver
import com.anaalarm.alarm.AlarmScheduleResult
import com.anaalarm.alarm.AlarmService
import com.anaalarm.alarm.Notifications
import com.anaalarm.ui.theme.AnaAlarmTheme

class WakeUpActivity : ComponentActivity() {

    private val sessionViewModel: WakeUpSessionViewModel by viewModels()
    private var directBootMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        dismissAlarmNotification()
        val alarmId = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L)
        val app = application as AnaAlarmApp
        if (!app.isUserUnlocked()) {
            directBootMode = true
            installDirectBootContent(alarmId)
        } else {
            check(app.ensureCredentialStorage())
            sessionViewModel.ensureSession(alarmId, voiceAvailable())
            installContent()
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        val newAlarmId = newIntent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L)
        setIntent(newIntent)
        dismissAlarmNotification(newIntent)
        val app = application as AnaAlarmApp
        if (!app.isUserUnlocked()) {
            directBootMode = true
            installDirectBootContent(newAlarmId)
            return
        }
        if (directBootMode) {
            transitionFromDirectBootIfUnlocked()
            return
        }
        if (newAlarmId == sessionViewModel.activeAlarmId) return

        // singleTask routes overlapping alarms here. Retire the old controller without stopping
        // the service, which already owns the newer alarm, then give the new ID fresh fallback
        // ownership and isolated conversation state.
        sessionViewModel.replaceSession(newAlarmId, voiceAvailable())
    }

    override fun onResume() {
        super.onResume()
        transitionFromDirectBootIfUnlocked()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) transitionFromDirectBootIfUnlocked()
    }

    private fun voiceAvailable(): Boolean {
        val app = application as AnaAlarmApp
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED && app.speechListener.isAvailable
    }

    private fun installContent() {
        setContent {
            val controller = sessionViewModel.controller
            AnaAlarmTheme(darkTheme = true) {
                controller?.let { WakeUpScreen(controller = it) }
            }
            LaunchedEffect(sessionViewModel.finishRequested) {
                if (sessionViewModel.finishRequested) finish()
            }
        }
    }

    private fun installDirectBootContent(alarmId: Long) {
        val app = application as AnaAlarmApp
        val snoozeMinutes = runCatching {
            app.alarmScheduler.directBootSnoozeMinutes(alarmId)
        }.getOrNull()
        setContent {
            AnaAlarmTheme(darkTheme = true) {
                DirectBootWakeUpScreen(
                    alarmId = alarmId,
                    snoozeMinutes = snoozeMinutes,
                    onSnooze = { snoozeDirectBootAlarm(alarmId) },
                    onStop = {
                        AlarmService.stop(applicationContext)
                        finish()
                    }
                )
            }
        }
    }

    private fun snoozeDirectBootAlarm(alarmId: Long): Boolean {
        val result = runCatching {
            (application as AnaAlarmApp)
                .alarmScheduler
                .scheduleDirectBootSnooze(alarmId)
        }.getOrElse {
            AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.SYSTEM_ERROR,
                it
            )
        }
        if (result !is AlarmScheduleResult.Scheduled) return false
        AlarmService.stop(applicationContext)
        finish()
        return true
    }

    private fun transitionFromDirectBootIfUnlocked() {
        if (!directBootMode) return
        val app = application as AnaAlarmApp
        if (!app.ensureCredentialStorage()) return

        directBootMode = false
        // USER_UNLOCKED also reconciles, but doing so here makes the visible wake flow robust if
        // that broadcast is delayed while this lock-screen activity already owns the foreground.
        app.alarmScheduler.rescheduleAll()
        val alarmId = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L)
        sessionViewModel.ensureSession(alarmId, voiceAvailable())
        installContent()
    }

    internal fun activeAlarmIdForTest(): Long = sessionViewModel.activeAlarmId
    internal fun controllerIdentityForTest(): Int =
        System.identityHashCode(sessionViewModel.controller)

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguard?.requestDismissKeyguard(this, null)
        }
    }

    private fun dismissAlarmNotification(sessionIntent: Intent = intent) {
        val alarmId = sessionIntent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L)
        if (alarmId >= 0) {
            NotificationManagerCompat.from(this)
                .cancel(Notifications.ALARM_NOTIFICATION_ID_BASE + alarmId.toInt())
        }
    }

}
