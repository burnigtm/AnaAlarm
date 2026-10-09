package com.anaalarm.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.data.AppSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AnaAlarmApp
    val memories by app.memoryStore.companionMemories.collectAsState(initial = emptyList())
    val settings by app.settingsStore.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    fun change(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try { action() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { snackbar.showSnackbar(context.getString(R.string.memory_failed)) }
            finally { saving = false }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(context.getString(R.string.memory_title)) }, navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, context.getString(R.string.back))
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(R.string.memory_enabled), modifier = Modifier.weight(1f))
                    Switch(checked = settings.memoryEnabled, enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = context.getString(R.string.memory_enabled) },
                        onCheckedChange = { enabled -> change { app.settingsStore.update(memoryEnabled = enabled) } })
                }
                Text(context.getString(R.string.memory_explanation), style = MaterialTheme.typography.bodyMedium)
            }
            if (memories.isEmpty()) item { Text(context.getString(R.string.memory_empty)) }
            items(memories, key = { it.id }) { memory ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(context.getString(if (memory.kind == "goal") R.string.memory_goal else R.string.memory_fact),
                            style = MaterialTheme.typography.labelLarge)
                        Text(memory.content)
                        Text(context.getString(if (memory.inferred) R.string.memory_inferred else R.string.memory_edited),
                            style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = !saving, onClick = { editingId = memory.id; draft = memory.content }) {
                                Text(context.getString(R.string.memory_edit))
                            }
                            TextButton(enabled = !saving, onClick = { change { app.memoryStore.forgetMemory(memory.id) } }) {
                                Text(context.getString(R.string.memory_forget))
                            }
                        }
                    }
                }
            }
        }
    }
    editingId?.let { id ->
        AlertDialog(
            onDismissRequest = { if (!saving) editingId = null },
            title = { Text(context.getString(R.string.memory_edit)) },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it.take(240) },
                label = { Text(context.getString(R.string.memory_content)) }) },
            confirmButton = { TextButton(enabled = draft.isNotBlank() && !saving, onClick = {
                change { app.memoryStore.editMemory(id, draft); editingId = null }
            }) { Text(context.getString(R.string.save)) } },
            dismissButton = { TextButton(enabled = !saving, onClick = { editingId = null }) {
                Text(context.getString(R.string.cancel))
            } }
        )
    }
}
