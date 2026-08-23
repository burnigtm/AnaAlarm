package com.anaalarm.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.ui.alarm.AlarmEditScreen
import com.anaalarm.ui.home.HomeScreen
import com.anaalarm.ui.settings.SettingsScreen
import com.anaalarm.ui.stats.StatsScreen
import com.anaalarm.ui.theme.AnaAlarmTheme
import com.anaalarm.ui.wakeup.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only activity that hosts real product screens (plus staged wake-up snapshots) so
 * documentation screenshots can be captured with `adb`. Not shipped in release.
 *
 *   adb shell am start -n com.anaalarm/.debug.DocsGalleryActivity --es scene home
 */
class DocsGalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val scene = intent.getStringExtra(EXTRA_SCENE) ?: SCENE_HOME
        if (isDark(scene)) setTheme(R.style.Theme_AnaAlarm_WakeUp)
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                if (needsSeed(scene)) {
                    runCatching {
                        DocsDemoData.seed(
                            app = application as AnaAlarmApp,
                            emptyHome = scene == SCENE_HOME_EMPTY
                        )
                    }.exceptionOrNull()
                } else {
                    null
                }
            }
            if (error != null) {
                Log.e(TAG, "Demo seed failed", error)
            }
            setContent {
                AnaAlarmTheme(darkTheme = isDark(scene)) {
                    LaunchedEffect(scene, error) {
                        Log.i(TAG, "ready scene=$scene")
                    }
                    if (error != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = error.message ?: error.javaClass.simpleName,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        DocsGalleryScene(scene)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    companion object {
        private const val TAG = "DocsGallery"
        const val EXTRA_SCENE = "scene"
        const val SCENE_HOME = "home"
        const val SCENE_HOME_EMPTY = "home_empty"
        const val SCENE_SETTINGS_BUDDY = "settings_buddy"
        const val SCENE_ALARM_EDIT = "alarm_edit"
        const val SCENE_STATS = "stats"
        const val SCENE_WAKE_SPEAKING = "wake_speaking"
        const val SCENE_WAKE_LISTENING = "wake_listening"
        const val SCENE_CHALLENGE_MATH = "challenge_math"
        const val SCENE_CHALLENGE_MEMORY = "challenge_memory"

        private fun isDark(scene: String): Boolean =
            scene.startsWith("wake_") || scene.startsWith("challenge_")

        private fun needsSeed(scene: String): Boolean =
            scene == SCENE_HOME ||
                scene == SCENE_HOME_EMPTY ||
                scene == SCENE_SETTINGS_BUDDY ||
                scene == SCENE_ALARM_EDIT ||
                scene == SCENE_STATS
    }
}

@Composable
private fun DocsGalleryScene(scene: String) {
    when (scene) {
        DocsGalleryActivity.SCENE_HOME,
        DocsGalleryActivity.SCENE_HOME_EMPTY -> HomeScreen(
            onEditAlarm = {},
            onAddAlarm = {},
            onSettings = {},
            onStats = {},
            onRequestAlarmPermission = {},
            onRequestFullScreenPermission = {}
        )
        DocsGalleryActivity.SCENE_SETTINGS_BUDDY -> SettingsScreen(
            onBack = {},
            scrollToBuddy = true
        )
        DocsGalleryActivity.SCENE_ALARM_EDIT -> AlarmEditScreen(
            alarmId = 1L,
            onBack = {},
            scrollToChallenge = true
        )
        DocsGalleryActivity.SCENE_STATS -> StatsScreen(onBack = {})
        DocsGalleryActivity.SCENE_WAKE_SPEAKING -> DocsWakeScene(
            status = SessionStatus.SPEAKING,
            species = DocsDefaultSpecies,
            aiText = "Good morning, Maya. You wanted the design review done before lunch — " +
                "Kiko is already stretching. How did you sleep?"
        )
        DocsGalleryActivity.SCENE_WAKE_LISTENING -> DocsWakeScene(
            status = SessionStatus.LISTENING,
            species = DocsDefaultSpecies,
            aiText = "Tell me the first thing you will actually do after you stand up.",
            userText = "Make coffee, then a ten-minute stretch."
        )
        DocsGalleryActivity.SCENE_CHALLENGE_MATH -> DocsWakeScene(
            status = SessionStatus.SPEAKING,
            species = DocsDefaultSpecies,
            aiText = "Prove you are awake and I will let you stop the alarm.",
            showChallenge = DocsChallengeKind.MATH
        )
        DocsGalleryActivity.SCENE_CHALLENGE_MEMORY -> DocsWakeScene(
            status = SessionStatus.SPEAKING,
            species = DocsDefaultSpecies,
            aiText = "Memorize the code, then type it back. No peeking.",
            showChallenge = DocsChallengeKind.MEMORY
        )
        else -> HomeScreen(
            onEditAlarm = {},
            onAddAlarm = {},
            onSettings = {},
            onStats = {},
            onRequestAlarmPermission = {},
            onRequestFullScreenPermission = {}
        )
    }
}
