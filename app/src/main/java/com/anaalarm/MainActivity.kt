package com.anaalarm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.anaalarm.ui.AppLocales
import com.anaalarm.ui.AppRoot
import com.anaalarm.ui.theme.AnaAlarmTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }

        syncStoredLanguageOnce()

        setContent {
            AnaAlarmTheme {
                AppRoot()
            }
        }
    }

    /**
     * Pushes the stored language into the system's per-app locale on Android 13+ — but only
     * while the user has not chosen one there themselves. Settings saves re-apply explicitly.
     */
    private fun syncStoredLanguageOnce() {
        val app = application as? AnaAlarmApp ?: return
        if (!app.ensureCredentialStorage()) return
        lifecycleScope.launch {
            val settings = runCatching { app.settingsStore.settings.first() }.getOrNull() ?: return@launch
            AppLocales.applyIfUnset(settings.language, this@MainActivity)
        }
    }
}
