package com.anaalarm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.anaalarm.ui.AppRoot
import com.anaalarm.ui.home.showMicRecoveryCard
import com.anaalarm.ui.theme.AnaAlarmTheme

class MainActivity : ComponentActivity() {

    private var micRequestDenied by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // A denial used to be ignored. Home turns it into an open-settings card.
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: return@registerForActivityResult
        micRequestDenied = !mic
    }

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

        setContent {
            AnaAlarmTheme {
                AppRoot(
                    micRecoveryVisible = showMicRecoveryCard(
                        permissionGranted = hasMicrophonePermission(),
                        userDeniedRequest = micRequestDenied
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasMicrophonePermission()) micRequestDenied = false
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

