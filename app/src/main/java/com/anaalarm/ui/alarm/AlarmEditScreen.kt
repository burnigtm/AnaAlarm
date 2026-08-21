package com.anaalarm.ui.alarm

import android.content.Intent
import android.media.RingtoneManager
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.alarm.AlarmScheduleResult
import com.anaalarm.data.AlarmEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(alarmId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AnaAlarmApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(7) }
    var minute by remember { mutableIntStateOf(0) }
    var days by remember { mutableIntStateOf(0) }
    var snooze by remember { mutableIntStateOf(10) }
    var enabled by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var challengeType by remember { mutableIntStateOf(0) }
    var maxSnoozes by remember { mutableIntStateOf(0) }
    var ringtoneUri by remember { mutableStateOf<String?>(null) }

    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        @Suppress("DEPRECATION")
        val picked = result.data?.getParcelableExtra<android.net.Uri>(
            RingtoneManager.EXTRA_RINGTONE_PICKED_URI
        )
        if (picked != null) ringtoneUri = picked.toString()
    }

    LaunchedEffect(alarmId) {
        val existing = if (alarmId >= 0) app.memoryStore.getAlarm(alarmId) else null
        if (existing != null) {
            hour = existing.hour
            minute = existing.minute
            days = existing.days
            snooze = existing.snoozeMinutes
            enabled = existing.enabled
            challengeType = existing.challengeType
            maxSnoozes = existing.maxSnoozes
            ringtoneUri = existing.ringtoneUri
        } else {
            val now = java.time.LocalDateTime.now().plusMinutes(2)
            hour = now.hour
            minute = now.minute
            val settings = app.settingsStore.settings.first()
            snooze = settings.snoozeMinutes
        }
        loaded = true
    }

    val isNew = alarmId < 0
    val dayNames = DayOfWeek.entries.map {
        it.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase().first().toString()
    }

    if (showTimePicker) {
        // Material3 picker follows the system 12/24-hour preference instead of forcing 24h.
        val timeState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = DateFormat.is24HourFormat(context)
        )
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = timeState)
                    Row {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text(context.getString(R.string.cancel))
                        }
                        Spacer(Modifier.size(12.dp))
                        Button(
                            onClick = {
                                hour = timeState.hour
                                minute = timeState.minute
                                showTimePicker = false
                            }
                        ) {
                            Text(context.getString(R.string.save))
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        context.getString(if (isNew) R.string.new_alarm else R.string.edit_alarm),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = context.getString(R.string.back),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (!loaded) return@Column

            Text(
                text = context.getString(R.string.time_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showTimePicker = true }) {
                Text(
                    text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = context.getString(R.string.repeat_days),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dayNames.forEachIndexed { index, label ->
                    val bit = (index + 1) % 7 // index 0 = Monday -> bit 1, index 6 = Sunday -> bit 0
                    FilterChip(
                        selected = (days and (1 shl bit)) != 0,
                        onClick = { days = days xor (1 shl bit) },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = context.getString(R.string.snooze_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${snooze} min", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = snooze.toFloat(),
                    onValueChange = { snooze = it.toInt() },
                    valueRange = 1f..30f,
                    steps = 28,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = context.getString(R.string.max_snoozes_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (maxSnoozes == 0) {
                        context.getString(R.string.challenge_none)
                    } else {
                        maxSnoozes.toString()
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(72.dp)
                )
                Slider(
                    value = maxSnoozes.toFloat(),
                    onValueChange = { maxSnoozes = it.toInt() },
                    valueRange = 0f..5f,
                    steps = 4,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = context.getString(R.string.ringtone_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ringtoneUri?.let { uri ->
                        RingtoneManager.getRingtone(context, android.net.Uri.parse(uri))
                            ?.getTitle(context)
                            ?: uri
                    } ?: context.getString(R.string.ringtone_default),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { ringtoneUri = null }) {
                    Text(context.getString(R.string.ringtone_default))
                }
                OutlinedButton(onClick = {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        ringtoneUri?.let { stored ->
                            runCatching {
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    android.net.Uri.parse(stored)
                                )
                            }
                        }
                    }
                    ringtonePicker.launch(intent)
                }) {
                    Text(context.getString(R.string.ringtone_pick))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = context.getString(R.string.challenge_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    0 to R.string.challenge_none,
                    1 to R.string.challenge_math,
                    2 to R.string.challenge_memory
                ).forEach { (code, labelRes) ->
                    FilterChip(
                        selected = challengeType == code,
                        onClick = { challengeType = code },
                        label = { Text(context.getString(labelRes)) }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            Row {
                Button(
                    onClick = {
                        scope.launch {
                            if (saving) return@launch
                            saving = true
                            var persistedId: Long? = null
                            var outcomeHandled = false
                            val rollbackUnscheduledSave: suspend (Long) -> Unit = { id ->
                                try {
                                    if (isNew) {
                                        app.memoryStore.deleteAlarm(id)
                                    } else {
                                        app.memoryStore.setAlarmEnabled(id, false)
                                    }
                                } catch (_: Exception) {
                                    // Best effort: still remove any stale PendingIntents below.
                                }
                                runCatching {
                                    app.alarmScheduler.cancel(
                                        AlarmEntity(
                                            id = id,
                                            hour = hour,
                                            minute = minute,
                                            days = days,
                                            snoozeMinutes = snooze,
                                            enabled = true
                                        )
                                    )
                                }
                            }
                            try {
                                val savedId = app.memoryStore.upsertAlarm(
                                    id = if (isNew) 0 else alarmId,
                                    hour = hour,
                                    minute = minute,
                                    days = days,
                                    snoozeMinutes = snooze,
                                    enabled = if (isNew) true else enabled,
                                    challengeType = challengeType,
                                    maxSnoozes = maxSnoozes,
                                    ringtoneUri = ringtoneUri
                                )
                                persistedId = savedId
                                val alarm = app.memoryStore.getAlarm(savedId)
                                val result = alarm?.let(app.alarmScheduler::schedule)
                                    ?: AlarmScheduleResult.Failed(
                                        AlarmScheduleResult.Failed.Reason.SYSTEM_ERROR
                                    )

                                when (result) {
                                    is AlarmScheduleResult.Scheduled -> {
                                        outcomeHandled = true
                                        val timeStr = String.format(
                                            Locale.getDefault(),
                                            "%02d:%02d",
                                            hour,
                                            minute
                                        )
                                        val today = java.time.LocalDate.now()
                                        val label = when {
                                            result.triggerAt.toLocalDate() == today ->
                                                context.getString(
                                                    R.string.alarm_scheduled_today,
                                                    timeStr
                                                )
                                            result.triggerAt.toLocalDate() == today.plusDays(1) ->
                                                context.getString(
                                                    R.string.alarm_scheduled_tomorrow,
                                                    timeStr
                                                )
                                            else -> context.getString(
                                                R.string.alarm_scheduled_day,
                                                result.triggerAt.dayOfWeek.getDisplayName(
                                                    TextStyle.SHORT,
                                                    Locale.getDefault()
                                                ),
                                                timeStr
                                            )
                                        }
                                        snackbar.showSnackbar(label)
                                        kotlinx.coroutines.delay(1400)
                                        onBack()
                                    }

                                    AlarmScheduleResult.Cancelled -> {
                                        outcomeHandled = true
                                        snackbar.showSnackbar(context.getString(R.string.saved))
                                        kotlinx.coroutines.delay(1400)
                                        onBack()
                                    }

                                    is AlarmScheduleResult.Failed -> {
                                        // Never leave an enabled database row that has no system
                                        // alarm. A failed new save is removed; an existing row is
                                        // retained disabled so the user's edits are not lost.
                                        rollbackUnscheduledSave(savedId)
                                        outcomeHandled = true
                                        val message = if (
                                            result.reason == AlarmScheduleResult.Failed.Reason
                                                .EXACT_ALARM_PERMISSION_REQUIRED
                                        ) {
                                            context.getString(R.string.perm_alarm_denied)
                                        } else {
                                            context.getString(R.string.alarm_schedule_failed)
                                        }
                                        snackbar.showSnackbar(message)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                if (!outcomeHandled) {
                                    persistedId?.let { id ->
                                        withContext(NonCancellable) {
                                            rollbackUnscheduledSave(id)
                                        }
                                    }
                                }
                                throw cancelled
                            } catch (_: Exception) {
                                if (!outcomeHandled) {
                                    persistedId?.let { rollbackUnscheduledSave(it) }
                                    outcomeHandled = true
                                }
                                snackbar.showSnackbar(
                                    context.getString(R.string.alarm_schedule_failed)
                                )
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving && loaded,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(context.getString(R.string.save))
                }
                Spacer(Modifier.size(12.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(context.getString(R.string.cancel))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
