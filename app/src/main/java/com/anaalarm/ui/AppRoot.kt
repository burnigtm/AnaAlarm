package com.anaalarm.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.anaalarm.ui.alarm.AlarmEditScreen
import com.anaalarm.ui.home.HomeScreen
import com.anaalarm.ui.settings.SettingsScreen

private sealed interface Screen {
    data object Home : Screen
    data class AlarmEdit(val alarmId: Long) : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val context = LocalContext.current

    when (val current = screen) {
        is Screen.Home -> HomeScreen(
            onEditAlarm = { id -> screen = Screen.AlarmEdit(id) },
            onAddAlarm = { screen = Screen.AlarmEdit(-1L) },
            onSettings = { screen = Screen.Settings },
            onRequestAlarmPermission = {
                requestExactAlarmPermission(context)
            },
            onRequestFullScreenPermission = {
                requestFullScreenIntentPermission(context)
            }
        )
        is Screen.AlarmEdit -> AlarmEditScreen(
            alarmId = current.alarmId,
            onBack = { screen = Screen.Home }
        )
        is Screen.Settings -> SettingsScreen(
            onBack = { screen = Screen.Home }
        )
    }
}

private fun requestExactAlarmPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } catch (_: Exception) {
            openAppDetails(context)
        }
    } else {
        openAppDetails(context)
    }
}

private fun requestFullScreenIntentPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        } catch (_: Exception) {
            // fall through
        }
    }
    openAppDetails(context)
}

private fun openAppDetails(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
