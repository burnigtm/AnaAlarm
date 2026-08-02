package com.anaalarm.ui.settings

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
import androidx.compose.material3.OutlinedTextField
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
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("en") }
    var habits by remember { mutableStateOf("") }
    var interests by remember { mutableStateOf("") }
    var sessionMinutes by remember { mutableIntStateOf(10) }

    LaunchedEffect(Unit) {
        val s = app.settingsStore.settings.first()
        apiKey = s.apiKey
        name = s.name
        language = s.language
        habits = s.habits.joinToString(", ")
        interests = s.interests.joinToString(", ")
        sessionMinutes = s.sessionMinutes
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
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle(context.getString(R.string.your_name))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Ana") },
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
            Button(
                onClick = {
                    scope.launch {
                        app.settingsStore.update(
                            apiKey = apiKey,
                            name = name,
                            language = language,
                            habits = habits.split(","),
                            interests = interests.split(","),
                            sessionMinutes = sessionMinutes
                        )
                        app.ttsManager.setLanguage(language)
                        snackbar.showSnackbar(context.getString(R.string.settings_saved))
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
