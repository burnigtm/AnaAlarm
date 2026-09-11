package com.anaalarm.ui.settings

import android.content.Context
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.ui.avatar.AnimalAvatar
import com.anaalarm.ui.avatar.AvatarMood
import com.anaalarm.ui.avatar.Avatars
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, scrollToBuddy: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as AnaAlarmApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var buddyY by remember { mutableIntStateOf(0) }
    LaunchedEffect(loaded, scrollToBuddy, buddyY) {
        if (loaded && scrollToBuddy && buddyY > 0) {
            scroll.scrollTo((buddyY - 16).coerceAtLeast(0))
        }
    }
    var apiKey by remember { mutableStateOf("") }
    var hasSavedKey by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("en") }
    var habits by remember { mutableStateOf("") }
    var interests by remember { mutableStateOf("") }
    var sessionMinutes by remember { mutableIntStateOf(10) }
    var pronouns by remember { mutableStateOf(com.anaalarm.data.Pronouns.NEUTRAL) }
    var tone by remember { mutableStateOf(com.anaalarm.data.Tones.UPBEAT) }
    var buddy by remember { mutableStateOf(Avatars.DEFAULT) }
    var voicePitch by remember { mutableFloatStateOf(1.05f) }
    var voiceRate by remember { mutableFloatStateOf(1.0f) }
    var streamingEnabled by remember { mutableStateOf(false) }
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
                buddy = Avatars.from(s.avatar)
                voicePitch = s.voicePitch
                voiceRate = s.voiceRate
                streamingEnabled = s.streamingEnabled
                // Import must take effect without requiring an extra Save (API 33+ UI + TTS).
                app.ttsManager.setLanguage(s.language)
                com.anaalarm.ui.AppLocales.apply(s.language, context)
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
        buddy = Avatars.from(s.avatar)
        voicePitch = s.voicePitch
        voiceRate = s.voiceRate
        streamingEnabled = s.streamingEnabled
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
                .verticalScroll(scroll)
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
            Column(
                modifier = Modifier.onGloballyPositioned {
                    buddyY = it.positionInParent().y.toInt()
                }
            ) {
                SectionTitle(context.getString(R.string.buddy_label))
                Text(
                    text = context.getString(R.string.buddy_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Avatars.ALL.forEach { id ->
                        BuddyCard(
                            speciesId = id,
                            selected = buddy == id,
                            onClick = { buddy = id },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
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

            Spacer(Modifier.height(24.dp))
            SectionTitle(context.getString(R.string.streaming_title))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.getString(R.string.streaming_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = context.getString(R.string.streaming_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = streamingEnabled,
                    onCheckedChange = { streamingEnabled = it }
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = context.getString(R.string.export_device_bound_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
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
                            voiceRate = voiceRate,
                            avatar = buddy,
                            streamingEnabled = streamingEnabled
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

/** Localized buddy display name. */
private fun buddyName(context: Context, id: String): String = context.getString(
    when (id) {
        Avatars.DINO -> R.string.buddy_dino
        Avatars.ZEBRA -> R.string.buddy_zebra
        else -> R.string.buddy_cheetah
    }
)

/**
 * Selectable buddy card with a live preview that cycles through a few moods so users can see
 * the character animate before committing. Cards are staggered so previews never move in sync.
 */
@Composable
private fun BuddyCard(
    speciesId: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val demoMoods = listOf(
        AvatarMood.NEUTRAL,
        AvatarMood.TALKING,
        AvatarMood.LISTENING,
        AvatarMood.THINKING,
        AvatarMood.HAPPY
    )
    var moodIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(speciesId) {
        // Stagger by position in ALL so the three cards animate out of phase.
        delay((Avatars.ALL.indexOf(speciesId) * 700L).coerceAtLeast(0L))
        while (true) {
            delay(1700)
            moodIndex = (moodIndex + 1) % demoMoods.size
        }
    }

    Card(
        modifier = modifier
            .testTag("buddy_card_$speciesId")
            // selectable() provides the canonical Selected semantics for accessibility and tests.
            .selectable(selected = selected, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimalAvatar(
                species = speciesId,
                mood = demoMoods[moodIndex],
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = buddyName(context, speciesId),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2
            )
            if (selected) {
                Spacer(Modifier.height(2.dp))
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
