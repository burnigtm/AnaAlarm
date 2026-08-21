package com.anaalarm.ui.home

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.alarm.AlarmScheduleResult
import com.anaalarm.alarm.AlarmTriggerCalculator
import com.anaalarm.alarm.Notifications
import com.anaalarm.data.AlarmEntity
import com.anaalarm.ui.wakeup.WakeUpActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onEditAlarm: (Long) -> Unit,
    onAddAlarm: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onRequestAlarmPermission: () -> Unit,
    onRequestFullScreenPermission: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AnaAlarmApp
    val vm: HomeViewModel = viewModel {
        HomeViewModel(app.memoryStore, app.alarmScheduler, app.settingsStore)
    }
    val alarms by vm.alarms.collectAsState()
    val upcoming by vm.upcomingAlarm.collectAsState()
    val recap by vm.recap.collectAsState()
    val habitNames by vm.habitNames.collectAsState()
    val habitsDone by vm.habitsDone.collectAsState()
    val streaks by vm.streaks.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.notices.collect { notice ->
            val message = when (notice) {
                is HomeNotice.ScheduleFailed -> when (notice.reason) {
                    AlarmScheduleResult.Failed.Reason.EXACT_ALARM_PERMISSION_REQUIRED ->
                        context.getString(R.string.perm_alarm_denied)
                    else -> context.getString(R.string.alarm_schedule_failed)
                }
            }
            snackbar.showSnackbar(message)
        }
    }

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    fun hasExactAlarmPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true
    var canExact by remember { mutableStateOf(hasExactAlarmPermission()) }
    var canFullScreen by remember {
        mutableStateOf(Notifications.canUseFullScreenIntent(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, alarmManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canExact = hasExactAlarmPermission()
                canFullScreen = Notifications.canUseFullScreenIntent(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var confirmDelete by remember { mutableStateOf<AlarmEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onStats) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = context.getString(R.string.stats_entry)
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = context.getString(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAlarm) {
                Icon(Icons.Default.Add, contentDescription = context.getString(R.string.add_alarm))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = context.getString(R.string.home_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(12.dp))

            if (!canExact) {
                PermissionWarningCard(
                    message = context.getString(R.string.perm_alarm_denied),
                    onRequest = onRequestAlarmPermission
                )
                Spacer(Modifier.height(12.dp))
            }
            if (!canFullScreen) {
                PermissionWarningCard(
                    message = context.getString(R.string.perm_fullscreen_denied),
                    onRequest = onRequestFullScreenPermission
                )
                Spacer(Modifier.height(12.dp))
            }
            if (shouldShowBatteryCard(context)) {
                BatteryWarningCard(
                    onOpenBatterySettings = { openBatterySettings(context) }
                )
                Spacer(Modifier.height(12.dp))
            }

            recap?.let { summary ->
                RecapCard(
                    summary = summary,
                    habitNames = habitNames,
                    habitsDone = habitsDone,
                    streaks = streaks,
                    onToggleHabit = { vm.toggleHabit(it) }
                )
                Spacer(Modifier.height(12.dp))
            }

            upcoming?.let { next ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = context.getString(R.string.next_alarm),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = formatUpcoming(context, next),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    context.startActivity(Intent(context, WakeUpActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(context.getString(R.string.test_session))
            }
            Text(
                text = context.getString(R.string.test_session_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = context.getString(R.string.alarms),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            if (alarms.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = context.getString(R.string.no_alarm),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmCard(
                            alarm = alarm,
                            onClick = { onEditAlarm(alarm.id) },
                            onToggle = { vm.toggleAlarm(alarm) },
                            onDelete = { confirmDelete = alarm }
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { alarm ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(context.getString(R.string.delete)) },
            text = { Text(formatTime(alarm.hour, alarm.minute)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAlarm(alarm)
                    confirmDelete = null
                }) { Text(context.getString(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
}
@Composable
private fun PermissionWarningCard(message: String, onRequest: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequest) {
                Text(context.getString(R.string.grant_permission))
            }
        }
    }
}

/** True while the OEM may still kill the app overnight (no battery exemption granted). */
private fun shouldShowBatteryCard(context: Context): Boolean =
    runCatching {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager != null &&
            !powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        openAppDetailsFallback(context)
    }
}

private fun openAppDetailsFallback(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun BatteryWarningCard(onOpenBatterySettings: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = context.getString(R.string.battery_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = context.getString(R.string.battery_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenBatterySettings) {
                Text(context.getString(R.string.battery_open))
            }
        }
    }
}

/** Morning recap: yesterday's durable summary plus today's tappable habit checklist. */
@Composable
private fun RecapCard(
    summary: String,
    habitNames: List<String>,
    habitsDone: Set<String>,
    streaks: Map<String, Int>,
    onToggleHabit: (String) -> Unit
) {
    val context = LocalContext.current
    if (summary.isBlank() && habitNames.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (summary.isNotBlank()) {
                Text(
                    text = context.getString(R.string.recap_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            if (habitNames.isNotEmpty()) {
                if (summary.isNotBlank()) Spacer(Modifier.height(12.dp))
                Text(
                    text = context.getString(R.string.recap_habits_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(8.dp))
                habitNames.forEach { name ->
                    val done = name in habitsDone
                    val streak = streaks[name] ?: 0
                    FilterChip(
                        selected = done,
                        onClick = { onToggleHabit(name) },
                        label = {
                            val label = if (streak > 0) "$name · $streak" else name
                            Text(label)
                        },
                        leadingIcon = if (done) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: AlarmEntity,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatTime(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (alarm.enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (alarm.days != 0) {
                    Text(
                        text = formatDays(context, alarm.days),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = alarm.enabled, onCheckedChange = { onToggle() })
            Spacer(Modifier.size(4.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = context.getString(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatTime(hour: Int, minute: Int): String =
    LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("HH:mm"))

private val DAY_LABELS = intArrayOf(
    R.string.day_sun,
    R.string.day_mon,
    R.string.day_tue,
    R.string.day_wed,
    R.string.day_thu,
    R.string.day_fri,
    R.string.day_sat
)

private fun formatDays(context: Context, days: Int): String =
    (0..6).filter { (days and (1 shl it)) != 0 }
        .joinToString(" ") { context.getString(DAY_LABELS[it]) }

private fun formatUpcoming(context: Context, upcoming: UpcomingAlarm): String {
    val timeStr = formatTime(upcoming.hour, upcoming.minute)
    val today = LocalDate.now()
    val date = upcoming.triggerAt.toLocalDate()
    return when {
        date == today -> context.getString(R.string.alarm_scheduled_today, timeStr)
        date == today.plusDays(1) -> context.getString(R.string.alarm_scheduled_tomorrow, timeStr)
        else -> context.getString(
            R.string.alarm_scheduled_day,
            context.getString(DAY_LABELS[AlarmTriggerCalculator.dayBit(upcoming.triggerAt.dayOfWeek)]),
            timeStr
        )
    }
}
