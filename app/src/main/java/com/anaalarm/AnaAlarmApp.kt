package com.anaalarm

import android.app.Application
import androidx.annotation.VisibleForTesting
import com.anaalarm.ai.ConversationEngine
import com.anaalarm.ai.DeepSeekClient
import com.anaalarm.alarm.AlarmScheduler
import com.anaalarm.alarm.Notifications
import com.anaalarm.data.AnaDatabase
import com.anaalarm.data.MemoryStore
import com.anaalarm.data.SettingsStore
import com.anaalarm.voice.SpeechListener
import com.anaalarm.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AnaAlarmApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var memoryStore: MemoryStore
        private set
    lateinit var deepSeekClient: DeepSeekClient
        private set
    lateinit var conversationEngine: ConversationEngine
        private set
    lateinit var ttsManager: TtsManager
        private set
    lateinit var speechListener: SpeechListener
        private set
    lateinit var alarmScheduler: AlarmScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)

        val db = AnaDatabase.get(this)
        settingsStore = SettingsStore(this)
        memoryStore = MemoryStore(db, settingsStore)
        deepSeekClient = DeepSeekClient(settingsStore)
        conversationEngine = ConversationEngine(deepSeekClient, memoryStore, settingsStore)
        ttsManager = TtsManager(this)
        speechListener = SpeechListener(this)
        alarmScheduler = AlarmScheduler(this)
        // Re-arm with setAlarmClock so existing alarms get full-screen wake behavior.
        alarmScheduler.rescheduleAll()
    }

    /**
     * Swaps the AI stack for one talking to [client]. Instrumented tests use this to point the
     * wake-up session at a local fake server; pass null to restore the real DeepSeek backend.
     */
    @VisibleForTesting
    fun overrideAiBackend(client: DeepSeekClient?) {
        deepSeekClient = client ?: DeepSeekClient(settingsStore)
        conversationEngine = ConversationEngine(deepSeekClient, memoryStore, settingsStore)
    }

    /** Rebuild TTS if the engine failed to bind (common after long idle / OEM kills). */
    fun recreateTts() {
        runCatching { ttsManager.shutdown() }
        ttsManager = TtsManager(this)
    }

    fun recreateSpeech() {
        runCatching { speechListener.destroy() }
        speechListener = SpeechListener(this)
    }
}
