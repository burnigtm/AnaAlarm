package com.anaalarm.ui.sentinel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.sentinel.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AnaAlarmApp
    val checker = remember(app) { AndroidSentinelHealth(app) }
    val exporter = remember(context) { AndroidSentinelReportExporter(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var health by remember { mutableStateOf<HealthSnapshot?>(null) }
    var events by remember { mutableStateOf<List<SentinelEvent>>(emptyList()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        busy = true
        try {
            health = checker.collect()
            events = app.sentinelHistory?.snapshot().orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            snackbar.showSnackbar(context.getString(R.string.sentinel_failed))
        } finally { busy = false }
    }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { snackbar.showSnackbar(context.getString(R.string.sentinel_failed)) }
            finally { busy = false }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { Text(context.getString(R.string.sentinel_title)) }, navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, context.getString(R.string.back))
                }
            })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(context.getString(R.string.sentinel_intro))
            Text(context.getString(R.string.sentinel_privacy), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { refresh++ }, enabled = !busy, modifier = Modifier.testTag("sentinel-refresh")) {
                Text(context.getString(R.string.sentinel_refresh))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            health?.results?.forEach { result ->
                Card(Modifier.fillMaxWidth().testTag("sentinel-${result.check.name}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(context.getString(checkTitle(result.check)), style = MaterialTheme.typography.titleMedium)
                        Text(context.getString(when (result.status) {
                            HealthStatus.READY -> R.string.sentinel_ready
                            HealthStatus.NEEDS_ATTENTION -> R.string.sentinel_attention
                            HealthStatus.UNAVAILABLE -> R.string.sentinel_unavailable
                        }), color = if (result.status == HealthStatus.NEEDS_ATTENTION) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(detail(context, result))
                        result.action?.let { target ->
                            TextButton(onClick = {
                                if (target == SettingsTarget.ANA_SETTINGS) onBack()
                                else action { openSettings(context, target) }
                            }, enabled = !busy) { Text(context.getString(R.string.sentinel_open_settings)) }
                        }
                    }
                }
            }
            Button(enabled = !busy && health != null, onClick = { action {
                @Suppress("DEPRECATION")
                val installedVersion = context.packageManager.getPackageInfo(context.packageName, 0).let {
                    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
                }
                val report = SentinelReport(versionCode = installedVersion,
                    health = checker.collect(), events = app.sentinelHistory?.snapshot().orEmpty())
                context.startActivity(Intent.createChooser(exporter.shareIntent(report), context.getString(R.string.sentinel_share)))
            } }, modifier = Modifier.testTag("sentinel-share")) { Text(context.getString(R.string.sentinel_share)) }
            Text(context.getString(R.string.sentinel_history), style = MaterialTheme.typography.titleMedium)
            if (events.isEmpty()) Text(context.getString(R.string.sentinel_no_events))
            events.asReversed().take(20).forEach { event ->
                Text(context.getString(R.string.sentinel_event,
                    DateFormat.getDateTimeInstance().format(Date(event.timestamp)),
                    context.getString(eventTitle(event.code)), context.getString(outcomeTitle(event.outcome)),
                    event.durationMillis?.let { context.getString(R.string.sentinel_duration, it) }.orEmpty()),
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(enabled = !busy, onClick = { action {
                app.sentinelHistory?.clear()
                withContext(Dispatchers.IO) {
                    File(context.cacheDir, "sentinel-reports").listFiles()?.forEach { it.delete() }
                }
                events = emptyList()
            } }, modifier = Modifier.testTag("sentinel-clear")) { Text(context.getString(R.string.sentinel_clear)) }
        }
    }
}

private fun openSettings(context: Context, target: SettingsTarget) {
    val uri = Uri.parse("package:${context.packageName}")
    val intent = when (target) {
        SettingsTarget.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri) else null
        SettingsTarget.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, uri) else null
        SettingsTarget.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        SettingsTarget.TTS -> Intent("com.android.settings.TTS_SETTINGS")
        else -> null
    }
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
    try { context.startActivity(intent ?: fallback) }
    catch (_: Exception) { context.startActivity(fallback) }
}

private fun detail(context: Context, result: HealthResult): String = when (result.detail) {
    HealthDetail.NEXT_TIME -> context.getString(R.string.sentinel_next_time,
        DateFormat.getDateTimeInstance().format(Date(result.value ?: 0)))
    else -> context.getString(when (result.detail) {
        HealthDetail.PASSED -> R.string.sentinel_passed
        HealthDetail.SETTING_REQUIRED -> R.string.sentinel_setting_required
        HealthDetail.SERVICE_AVAILABLE -> R.string.sentinel_service_available
        HealthDetail.SERVICE_MISSING -> R.string.sentinel_service_missing
        HealthDetail.KEY_MISSING -> R.string.sentinel_key_missing
        HealthDetail.ALARM_COUNT -> R.string.sentinel_alarm_count
        HealthDetail.OTHER_APP_NEXT -> R.string.sentinel_other_app
        HealthDetail.NO_NEXT_ALARM -> R.string.sentinel_no_next
        HealthDetail.LOCKED -> R.string.sentinel_locked
        HealthDetail.CHECK_FAILED -> R.string.sentinel_check_failed
        HealthDetail.EXIT_COUNT -> R.string.sentinel_exit_count
        HealthDetail.UNSUPPORTED -> R.string.sentinel_unsupported
        HealthDetail.NEXT_TIME -> error("Handled above")
    }, result.value ?: 0)
}

private fun checkTitle(check: HealthCheck): Int = when (check) {
    HealthCheck.EXACT_ALARMS -> R.string.sentinel_exact
    HealthCheck.NOTIFICATIONS -> R.string.sentinel_notifications
    HealthCheck.ALARM_CHANNEL -> R.string.sentinel_alarm_channel
    HealthCheck.SESSION_CHANNEL -> R.string.sentinel_session_channel
    HealthCheck.FULL_SCREEN -> R.string.sentinel_full_screen
    HealthCheck.BATTERY -> R.string.sentinel_battery
    HealthCheck.MICROPHONE -> R.string.sentinel_microphone
    HealthCheck.SPEECH -> R.string.sentinel_speech
    HealthCheck.TTS -> R.string.sentinel_tts
    HealthCheck.API_KEY -> R.string.sentinel_api_key
    HealthCheck.ENABLED_ALARMS -> R.string.sentinel_enabled
    HealthCheck.NEXT_ALARM -> R.string.sentinel_next
    HealthCheck.PROCESS_EXITS -> R.string.sentinel_exits
}

private fun eventTitle(code: EventCode): Int = when (code) {
    EventCode.ALARM_SCHEDULED -> R.string.sentinel_event_scheduled
    EventCode.ALARM_SCHEDULE_FAILED -> R.string.sentinel_event_schedule_failed
    EventCode.ALARM_PLAYBACK_FAILED -> R.string.sentinel_event_playback_failed
    EventCode.ALARM_TO_AUDIO -> R.string.sentinel_event_audio
    EventCode.ASR_RESULT -> R.string.sentinel_event_asr
    EventCode.MODEL_COMPLETION -> R.string.sentinel_event_model
    EventCode.MODEL_FIRST_TEXT -> R.string.sentinel_event_first_text
    EventCode.TTS_START -> R.string.sentinel_event_tts
}

private fun outcomeTitle(outcome: EventOutcome): Int = when (outcome) {
    EventOutcome.STARTED -> R.string.sentinel_outcome_started
    EventOutcome.COMPLETED -> R.string.sentinel_outcome_completed
    EventOutcome.RESULT -> R.string.sentinel_outcome_result
    EventOutcome.FAILED -> R.string.sentinel_outcome_failed
    EventOutcome.ERROR -> R.string.sentinel_outcome_error
    EventOutcome.CANCELLED -> R.string.sentinel_outcome_cancelled
    EventOutcome.TIMED_OUT -> R.string.sentinel_outcome_timed_out
    EventOutcome.UNKNOWN -> R.string.sentinel_outcome_unknown
}
