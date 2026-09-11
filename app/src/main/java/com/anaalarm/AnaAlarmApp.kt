package com.anaalarm

import android.app.Application
import android.os.UserManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.anaalarm.ai.ConversationEngine
import com.anaalarm.ai.DeepSeekClient
import com.anaalarm.alarm.AlarmScheduler
import com.anaalarm.alarm.Notifications
import com.anaalarm.data.AnaDatabase
import com.anaalarm.data.MemoryStore
import com.anaalarm.data.SettingsStore
import com.anaalarm.ui.AppLocales
import com.anaalarm.voice.SpeechListener
import com.anaalarm.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnaAlarmApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var memoryStore: MemoryStore
        private set
    @Volatile
    private var deepSeekClientInstance: DeepSeekClient? = null
    val deepSeekClient: DeepSeekClient
        get() {
            // settingsStore/memoryStore are lateinit and only valid after first unlock; a caller
            // reaching the AI stack before that is a programming error worth failing on loudly.
            check(credentialStorageReady) {
                "AI stack accessed before ensureCredentialStorage() (device still locked?)"
            }
            return deepSeekClientInstance ?: synchronized(this) {
                deepSeekClientInstance ?: DeepSeekClient(settingsStore).also {
                    deepSeekClientInstance = it
                }
            }
        }

    @Volatile
    private var conversationEngineInstance: ConversationEngine? = null
    val conversationEngine: ConversationEngine
        get() = conversationEngineInstance ?: synchronized(this) {
            conversationEngineInstance ?: newConversationEngine().also {
                conversationEngineInstance = it
            }
        }

    @Volatile
    private var ttsManagerInstance: TtsManager? = null
    var ttsManager: TtsManager
        get() = ttsManagerInstance ?: synchronized(this) {
            ttsManagerInstance ?: TtsManager(this).also { ttsManagerInstance = it }
        }
        private set(value) {
            ttsManagerInstance = value
        }

    @Volatile
    private var speechListenerInstance: SpeechListener? = null
    var speechListener: SpeechListener
        get() = speechListenerInstance ?: synchronized(this) {
            speechListenerInstance ?: SpeechListener(this).also { speechListenerInstance = it }
        }
        private set(value) {
            speechListenerInstance = value
        }
    lateinit var alarmScheduler: AlarmScheduler
        private set

    @Volatile
    private var credentialStorageReady = false

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        alarmScheduler = AlarmScheduler(this)
        if (ensureCredentialStorage()) {
            // Reconcile Room, the direct-boot mirror, and AlarmManager on every normal process start.
            alarmScheduler.rescheduleAll()
            // Restore the in-app language after kill/relaunch (API 33+). No-ops when already effective.
            applicationScope.launch {
                val language = runCatching { settingsStore.settings.first().language }.getOrNull()
                    ?: return@launch
                withContext(Dispatchers.Main.immediate) {
                    AppLocales.applyIfUnset(language, this@AnaAlarmApp)
                }
            }
        } else {
            Log.i(TAG, "Credential storage remains unopened until USER_UNLOCKED")
        }
    }

    /** True only after the first device unlock following boot. */
    fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked != false

    /**
     * Initializes Room/DataStore only when credential-encrypted storage is legally available.
     * Direct-boot receivers, the alarm service, and the minimal wake UI must not call these fields
     * until this returns true.
     */
    fun ensureCredentialStorage(): Boolean {
        if (!isUserUnlocked()) return false
        if (credentialStorageReady) return true
        synchronized(this) {
            if (credentialStorageReady) return true
            val db = AnaDatabase.get(this)
            settingsStore = SettingsStore(this)
            memoryStore = MemoryStore(db, settingsStore)
            credentialStorageReady = true
            applicationScope.launch { settingsStore.migrateLegacyApiKey() }
        }
        return true
    }

    internal fun isCredentialStorageReadyForTest(): Boolean = credentialStorageReady

    /**
     * Swaps the AI stack for one talking to [client]. Instrumented tests use this to point the
     * wake-up session at a local fake server; pass null to restore the real DeepSeek backend.
     */
    @VisibleForTesting
    fun overrideAiBackend(client: DeepSeekClient?) {
        synchronized(this) {
            deepSeekClientInstance = client
            conversationEngineInstance = client?.let {
                ConversationEngine(it, memoryStore, settingsStore)
            }
        }
    }

    /** Each wake activity owns isolated mutable conversation state; network/storage are shared. */
    fun newConversationEngine(): ConversationEngine =
        ConversationEngine(deepSeekClient, memoryStore, settingsStore)

    /** Rebuild TTS if the engine failed to bind (common after long idle / OEM kills). */
    fun recreateTts() {
        synchronized(this) {
            ttsManagerInstance?.let { runCatching { it.shutdown() } }
            ttsManager = TtsManager(this)
        }
    }

    fun recreateSpeech() {
        synchronized(this) {
            speechListenerInstance?.let { runCatching { it.destroy() } }
            speechListener = SpeechListener(this)
        }
    }

    private companion object {
        const val TAG = "AnaAlarm"
    }
}
