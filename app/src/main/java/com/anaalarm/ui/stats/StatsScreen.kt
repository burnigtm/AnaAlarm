package com.anaalarm.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.data.SessionRecordEntity
import com.anaalarm.data.TokenUsageSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Local statistics over completed wake-up sessions and token usage. Read-only, loaded once per
 * visit; every number comes from the on-device ledger, never from the network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AnaAlarmApp

    var records by remember { mutableStateOf<List<SessionRecordEntity>>(emptyList()) }
    var usageToday by remember { mutableStateOf<TokenUsageSummary?>(null) }
    var usageWeek by remember { mutableStateOf<TokenUsageSummary?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        records = runCatching { app.memoryStore.recentSessionRecords(limit = 30) }
            .getOrDefault(emptyList())
        usageToday = runCatching { app.memoryStore.usageSummary(daysBack = 0) }.getOrNull()
        usageWeek = runCatching { app.memoryStore.usageSummary(daysBack = 6) }.getOrNull()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        context.getString(R.string.stats_title),
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (!loaded) return@Column

            val zone = ZoneId.systemDefault()
            val weekAgo = LocalDate.now().minusDays(6)
            val weekRecords = records.filter {
                Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() >= weekAgo
            }
            val avgMinutes = weekRecords
                .map { it.durationMs / 60_000.0 }
                .takeIf { it.isNotEmpty() }
                ?.average()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = context.getString(R.string.stat_sessions_week),
                    value = weekRecords.size.toString()
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = context.getString(R.string.stat_avg_duration),
                    value = avgMinutes?.let { String.format("%.0f min", it) } ?: "—"
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = context.getString(R.string.stat_tokens_week),
                    value = usageWeek?.let { String.format("%,d", it.totalTokens) } ?: "—"
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = context.getString(R.string.stats_recent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            if (records.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = context.getString(R.string.stats_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                records.forEach { record ->
                    SessionRow(record)
                    Spacer(Modifier.height(6.dp))
                }
            }

            usageToday?.let { today ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = context.getString(
                        R.string.usage_today,
                        String.format(java.util.Locale.getDefault(), "%,d", today.totalTokens),
                        today.requests
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionRow(record: SessionRecordEntity) {
    val startedAt = Instant.ofEpochMilli(record.startedAt).atZone(ZoneId.systemDefault())
    val timeText = startedAt.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${record.durationMs / 60_000} min",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(0.dp))
            Text(
                text = "  ·  ${record.turns}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
