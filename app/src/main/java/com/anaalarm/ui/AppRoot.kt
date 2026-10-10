package com.anaalarm.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.anaalarm.ui.alarm.AlarmEditScreen
import com.anaalarm.ui.home.HomeScreen
import com.anaalarm.ui.settings.SettingsScreen
import com.anaalarm.ui.stats.StatsScreen

private sealed interface Screen {
    data object Home : Screen
    data class AlarmEdit(val alarmId: Long) : Screen
    data object Settings : Screen
    data object Stats : Screen
    data object Memories : Screen
    data object Sentinel : Screen
}

@Composable
fun AppRoot(requestedAlarmId: Long? = null) {
    val screenSaver = Saver<Screen, String>(
        save = { when (it) {
            Screen.Home -> "home"
            Screen.Settings -> "settings"
            Screen.Stats -> "stats"
            Screen.Memories -> "memories"
            Screen.Sentinel -> "sentinel"
            is Screen.AlarmEdit -> "alarm:${it.alarmId}"
        } },
        restore = { when (it) {
            "settings" -> Screen.Settings
            "stats" -> Screen.Stats
            "memories" -> Screen.Memories
            "sentinel" -> Screen.Sentinel
            else -> if (it.startsWith("alarm:")) Screen.AlarmEdit(it.substringAfter(':').toLong()) else Screen.Home
        } }
    )
    var screen by rememberSaveable(stateSaver = screenSaver) { mutableStateOf<Screen>(Screen.Home) }
    var handledRequest by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(requestedAlarmId) {
        if (requestedAlarmId != null && requestedAlarmId != handledRequest) {
            screen = Screen.AlarmEdit(requestedAlarmId)
            handledRequest = requestedAlarmId
        }
    }
    val context = LocalContext.current
    val credentialDraft: com.anaalarm.ui.settings.SettingsCredentialDraft = androidx.lifecycle.viewmodel.compose.viewModel()
    val stateHolder = rememberSaveableStateHolder()
    fun routeKey(route: Screen) = when (route) {
        is Screen.AlarmEdit -> "alarm:${route.alarmId}"
        else -> route.toString()
    }
    fun backToHome() {
        if (screen == Screen.Settings) credentialDraft.key.value = ""
        stateHolder.removeState(routeKey(screen))
        screen = Screen.Home
    }
    BackHandler(enabled = screen != Screen.Home) {
        if (screen == Screen.Memories || screen == Screen.Sentinel) screen = Screen.Settings else backToHome()
    }
    stateHolder.SaveableStateProvider(routeKey(screen)) {

        when (val current = screen) {
            is Screen.Home -> HomeScreen(
                onEditAlarm = { id -> screen = Screen.AlarmEdit(id) },
                onAddAlarm = { screen = Screen.AlarmEdit(-1L) },
                onSettings = { screen = Screen.Settings },
                onStats = { screen = Screen.Stats },
                onRequestAlarmPermission = {
                    requestExactAlarmPermission(context)
                },
                onRequestFullScreenPermission = {
                    requestFullScreenIntentPermission(context)
                }
            )
            is Screen.AlarmEdit -> AlarmEditScreen(
                alarmId = current.alarmId,
                onBack = { backToHome() }
            )
            is Screen.Settings -> SettingsScreen(
                onBack = { backToHome() },
                onMemories = { screen = Screen.Memories },
                onSentinel = { screen = Screen.Sentinel }
            )
            is Screen.Stats -> StatsScreen(
                onBack = { backToHome() }
            )
            is Screen.Memories -> com.anaalarm.ui.memory.MemoryScreen(onBack = { screen = Screen.Settings })
            is Screen.Sentinel -> com.anaalarm.ui.sentinel.SentinelScreen(onBack = { screen = Screen.Settings })
        }
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
