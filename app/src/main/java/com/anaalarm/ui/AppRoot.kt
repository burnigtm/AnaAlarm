package com.anaalarm.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
}

@Composable
fun AppRoot(micRecoveryVisible: Boolean = false) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val context = LocalContext.current
    // CI emulators and the system "remove animations" setting set this to 0. AnimatedContent
    // then never settles on the target, so those screens stay offstage.
    val animationsOff = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }

    // Nested screens previously finished MainActivity on system Back; keep them on Home instead.
    // Alarm edit installs its own handler while unsaved changes need a confirm dialog.
    BackHandler(enabled = screen !is Screen.Home && screen !is Screen.AlarmEdit) {
        screen = Screen.Home
    }

    if (animationsOff) {
        Box(Modifier.fillMaxSize()) {
            MainDestination(
                current = screen,
                micRecoveryVisible = micRecoveryVisible,
                onNavigate = { screen = it }
            )
        }
    } else {
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val backHome = targetState is Screen.Home && initialState !is Screen.Home
                val enter = slideInHorizontally(tween(220)) { full ->
                    if (backHome) -full / 5 else full / 5
                } + fadeIn(tween(220))
                val exit = slideOutHorizontally(tween(220)) { full ->
                    if (backHome) full / 5 else -full / 5
                } + fadeOut(tween(180))
                enter togetherWith exit
            },
            label = "mainScreens"
        ) { current ->
            MainDestination(
                current = current,
                micRecoveryVisible = micRecoveryVisible,
                onNavigate = { screen = it }
            )
        }
    }
}

@Composable
private fun MainDestination(
    current: Screen,
    micRecoveryVisible: Boolean,
    onNavigate: (Screen) -> Unit
) {
    val context = LocalContext.current
    when (current) {
        is Screen.Home -> HomeScreen(
            onEditAlarm = { id -> onNavigate(Screen.AlarmEdit(id)) },
            onAddAlarm = { onNavigate(Screen.AlarmEdit(-1L)) },
            onSettings = { onNavigate(Screen.Settings) },
            onStats = { onNavigate(Screen.Stats) },
            onRequestAlarmPermission = {
                requestExactAlarmPermission(context)
            },
            onRequestFullScreenPermission = {
                requestFullScreenIntentPermission(context)
            },
            micRecoveryVisible = micRecoveryVisible,
            onOpenAppSettings = { openAppDetails(context) }
        )
        is Screen.AlarmEdit -> AlarmEditScreen(
            alarmId = current.alarmId,
            onBack = { onNavigate(Screen.Home) }
        )
        is Screen.Settings -> SettingsScreen(
            onBack = { onNavigate(Screen.Home) }
        )
        is Screen.Stats -> StatsScreen(
            onBack = { onNavigate(Screen.Home) }
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
