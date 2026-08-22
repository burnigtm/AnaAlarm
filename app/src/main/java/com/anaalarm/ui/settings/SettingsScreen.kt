package com.anaalarm.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AnaAlarmApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var hasSavedKey by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("en") }
    var habits by remember { mutableStateOf("") }
    var interests by remember { mutableStateOf("") }
    var sessionMinutes by remember { mutableIntStateOf(10) }
    var pronouns by remember { mutableStateOf(com.anaalarm.data.Pronouns.NEUTRAL) }
    var tone by remember { mutableStateOf(com.anaalarm.data.Tones.UPBEAT) }
    var voicePitch by remember { mutableFloatStateOf(1.05f) }
    var voiceRate by remember { mutableFloatStateOf(1.0f) }
    var usageToday by remember {
        mutableStateOf<com.anaalarm.data.TokenUsageSummary?>(null)
    }
    var usageWeek by remember {
        mutableStateOf<com.anaalarm.data.TokenUsageSummary?>(null)
    }
    val snackbarState = snackbar

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(
                        com.anaalarm.data.DataExport.serialize(
                            com.anaalarm.data.DataExport.build(app.memoryStore, app.settingsStore)
                        ).toByteArray(Charsets.UTF_8)
                    )
                } ?: error("no output stream")
            }
            snackbarState.showSnackbar(
                context.getString(
                    if (result.isSuccess) R.string.export_done else R.string.export_import_failed
                )
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: error("no input stream")
                val payload = com.anaalarm.data.DataExport.deserialize(text)
                    ?: error("unrecognized file")
                com.anaalarm.data.DataExport.restore(payload, app.memoryStore, app.settingsStore)
            }
            snackbarState.showSnackbar(
                context.getString(
                    if (result.isSuccess) R.string.import_done else R.string.export_import_failed
                )
            )
            if (result.isSuccess) {
                // Reload the form so restored settings are visible immediately.
                val s = app.settingsStore.settings.first()
                name = s.name
                language = s.language
                habits = s.habits.joinToString(", ")
                interests = s.interests.joinToString(", ")
                sessionMinutes = s.sessionMinutes
                pronouns = s.pronouns
                tone = s.tone
                voicePitch = s.voicePitch
                voiceRate = s.voiceRate
            }
        }
    }

    LaunchedEffect(Unit) {
        val s = app.settingsStore.settings.first()
        // The stored credential is never echoed back into the text field. An empty field means
        // "keep the existing key"; typing replaces it; the remove action clears it explicitly.
        hasSavedKey = s.apiKey.isNotBlank()
        name = s.name
        language = s.language
        habits = s.habits.joinToString(", ")
        interests = s.interests.joinToString(", ")
        sessionMinutes = s.sessionMinutes
        pronouns = s.pronouns
        tone = s.tone
        voicePitch = s.voicePitch
        voiceRate = s.voiceRate
        // Dashboard numbers load once per visit; they are informational, not live telemetry.
        usageToday = runCatching { app.memoryStore.usageSummary(daysBack = 0) }.getOrNull()
        usageWeek = runCatching { app.memoryStore.usageSummary(daysBack = 6) }.getOrNull()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        context.getString(R.string.settings),
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

            SectionTitle(context.getString(R.string.api_key))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(context.getString(R.string.api_key_hint)) },
                supportingText = {
                    if (hasSavedKey) Text(context.getString(R.string.api_key_saved_hint))
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (hasSavedKey) {
                TextButton(onClick = {
                    hasSavedKey = false
                    apiKey = ""
                    scope.launch {
                        app.settingsStore.update(apiKey = "")
                        snackbar.showSnackbar(context.getString(R.string.settings_saved))
                    }
                }) {
                    Text(context.getString(R.string.api_key_remove))
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle(context.getString(R.string.your_name))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(context.getString(R.string.name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle(context.getString(R.string.language_label))
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = language == "en",
                    onClick = { language = "en" },
                    label = { Text(context.getString(R.string.language_en)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = language == "pt",
                    onClick = { language = "pt" },
                    label = { Text(context.getString(R.string.language_pt)) }
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle(context.getString(R.string.pronouns_label))
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = pronouns == com.anaalarm.data.Pronouns.NEUTRAL,
                    onClick = { pronouns = com.anaalarm.data.Pronouns.NEUTRAL },
                    label = { Text(context.getString(R.string.pronouns_neutral)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = pronouns == com.anaalarm.data.Pronouns.SHE,
                    onClick = { pronouns = com.anaalarm.data.Pronouns.SHE },
                    label = { Text(context.getString(R.string.pronouns_she)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = pronouns == com.anaalarm.data.Pronouns.HE,
                    onClick = { pronouns = com.anaalarm.data.Pronouns.HE },
                    label = { Text(context.getString(R.string.pronouns_he)) }
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle(context.getString(R.string.tone_label))
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = tone == com.anaalarm.data.Tones.GENTLE,
                    onClick = { tone = com.anaalarm.data.Tones.GENTLE },
                    label = { Text(context.getString(R.string.tone_gentle)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = tone == com.anaalarm.data.Tones.UPBEAT,
                    onClick = { tone = com.anaalarm.data.Tones.UPBEAT },
                    label = { Text(context.getString(R.string.tone_upbeat)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = tone == com.anaalarm.data.Tones.DRILL,
                    onClick = { tone = com.anaalarm.data.Tones.DRILL },
                    label = { Text(context.getString(R.string.tone_drill)) }
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle(context.getString(R.string.voice_pitch))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(java.util.Locale.getDefault(), "%.2f", voicePitch),
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = voicePitch,
                    onValueChange = { voicePitch = it },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
            }

            SectionTitle(context.getString(R.string.voice_rate))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(java.util.Locale.getDefault(), "%.2f", voiceRate),
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = voiceRate,
                    onValueChange = { voiceRate = it },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle(context.getString(R.string.habits_label))
            OutlinedTextField(
                value = habits,
                onValueChange = { habits = it },
                label = { Text(context.getString(R.string.habits_hint)) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle(context.getString(R.string.interests_label))
            OutlinedTextField(
                value = interests,
                onValueChange = { interests = it },
                label = { Text(context.getString(R.string.interests_hint)) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle(context.getString(R.string.session_minutes))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$sessionMinutes min",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = sessionMinutes.toFloat(),
                    onValueChange = { sessionMinutes = it.toInt() },
                    valueRange = 5f..15f,
                    steps = 9,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle(context.getString(R.string.usage_title))
            usageToday?.let { today ->
                Text(
                    text = context.getString(
                        R.string.usage_today,
                        formatTokens(today.totalTokens),
                        today.requests
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            usageWeek?.let { week ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = context.getString(
                        R.string.usage_week,
                        formatTokens(week.totalTokens),
                        formatCost(com.anaalarm.data.UsageCostEstimates.estimateUsd(week))
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = context.getString(R.string.usage_cost_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(
                    onClick = { exportLauncher.launch("anaalarm-export.txt") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(context.getString(R.string.export_data))
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("text/plain")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(context.getString(R.string.import_data))
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    scope.launch {
                        // A blank field with a stored key means "keep it"; only typed or
                        // explicitly removed credentials are written.
                        val keyToPersist = if (apiKey.isBlank() && hasSavedKey) null else apiKey
                        app.settingsStore.update(
                            apiKey = keyToPersist,
                            name = name,
                            language = language,
                            habits = habits.split(","),
                            interests = interests.split(","),
                            sessionMinutes = sessionMinutes,
                            pronouns = pronouns,
                            tone = tone,
                            voicePitch = voicePitch,
                            voiceRate = voiceRate
                        )
                        app.ttsManager.setLanguage(language)
                        snackbar.showSnackbar(context.getString(R.string.settings_saved))
                        // Android 13+: applying a new per-app locale recreates this activity so
                        // the interface relabels — deliberately last, after the confirmation.
                        com.anaalarm.ui.AppLocales.apply(language, context)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(context.getString(R.string.save))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(6.dp))
}

private fun formatTokens(tokens: Long): String =
    String.format(java.util.Locale.getDefault(), "%,d", tokens)

private fun formatCost(usd: Double): String =
    "$" + String.format(java.util.Locale.getDefault(), "%.4f", usd)
