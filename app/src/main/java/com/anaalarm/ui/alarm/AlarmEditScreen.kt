package com.anaalarm.ui.alarm

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

    LaunchedEffect(alarmId) {
        val existing = if (alarmId >= 0) app.memoryStore.getAlarm(alarmId) else null
        if (existing != null) {
            hour = existing.hour
            minute = existing.minute
            days = existing.days
            snooze = existing.snoozeMinutes
            enabled = existing.enabled
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

    fun showTimePicker() {
        TimePickerDialog(
            context,
            { _, h, m -> hour = h; minute = m },
            hour,
            minute,
            true
        ).show()
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
            Text(
                text = context.getString(R.string.time_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showTimePicker() }) {
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
                                    enabled = if (isNew) true else enabled
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
                    enabled = !saving,
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
