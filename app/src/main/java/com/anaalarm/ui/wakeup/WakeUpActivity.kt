package com.anaalarm.ui.wakeup

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anaalarm.AnaAlarmApp
import com.anaalarm.alarm.AlarmReceiver
import com.anaalarm.alarm.Notifications
import com.anaalarm.ui.theme.AnaAlarmTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class WakeUpActivity : ComponentActivity() {

    private val scope = MainScope()
    private lateinit var controller: SessionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        dismissAlarmNotification()

        val app = application as AnaAlarmApp
        val voiceAvailable = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED && app.speechListener.isAvailable

        controller = SessionController(
            app = app,
            scope = scope,
            onFinished = { finish() },
            voiceAvailable = voiceAvailable
        )

        setContent {
            AnaAlarmTheme(darkTheme = true) {
                WakeUpScreen(controller = controller)
            }
        }

        controller.start()
    }

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

    private fun dismissAlarmNotification() {
        val alarmId = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L)
        if (alarmId >= 0) {
            NotificationManagerCompat.from(this)
                .cancel(Notifications.ALARM_NOTIFICATION_ID_BASE + alarmId.toInt())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as AnaAlarmApp).ttsManager.stop()
        (application as AnaAlarmApp).speechListener.stopListening()
        scope.cancel()
    }
}
