package com.anaalarm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.anaalarm.ui.AppRoot
import com.anaalarm.ui.theme.AnaAlarmTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_SHOW_ALARM = "com.anaalarm.action.SHOW_ALARM"
        const val EXTRA_EDIT_ALARM_ID = "edit_alarm_id"
    }
    private val requestedAlarm = mutableStateOf<Long?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedAlarm.value = intent.getLongExtra(EXTRA_EDIT_ALARM_ID, -1L).takeIf { it >= 0L }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedAlarm.value = intent.getLongExtra(EXTRA_EDIT_ALARM_ID, -1L).takeIf { it >= 0L }

        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }

        setContent {
            AnaAlarmTheme {
                AppRoot(requestedAlarm.value)
            }
        }
    }
}

