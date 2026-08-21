package com.anaalarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "anaalarm_settings")

data class AppSettings(
    val apiKey: String = "",
    val name: String = "",
    val language: String = "en",
    val habits: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val sessionMinutes: Int = 10,
    val snoozeMinutes: Int = 10,
    /** One of [Pronouns.VALUES]; drives the persona prompt's third-person forms. */
    val pronouns: String = Pronouns.NEUTRAL,
    /**
     * Streams model responses with phrase-level TTS overlap. Provider-capability flag kept
     * off by default per docs/RELIABILITY_AND_LATENCY.md until rollout is explicitly enabled.
     */
    val streamingEnabled: Boolean = false,
    /** One of [Tones.VALUES]; shapes Ana's energy in the system prompt. */
    val tone: String = Tones.UPBEAT,
    /** TTS voice shaping, clamped by [com.anaalarm.voice.TtsManager] on use. */
    val voicePitch: Float = 1.05f,
    val voiceRate: Float = 1.0f
)

/** Selectable persona tones for the wake-up prompt. */
object Tones {
    const val GENTLE = "gentle"
    const val UPBEAT = "upbeat"
    const val DRILL = "drill"
    val VALUES = listOf(GENTLE, UPBEAT, DRILL)
}

/** User-selectable third-person pronoun forms used by [com.anaalarm.ai.PromptBuilder]. */
object Pronouns {
    const val NEUTRAL = "neutral"
    const val SHE = "she"
    const val HE = "he"
    val VALUES = listOf(NEUTRAL, SHE, HE)

    data class Forms(val subject: String, val possessive: String, val beVerb: String)

    fun forms(setting: String): Forms = when (setting) {
        SHE -> Forms("she", "her", "is")
        HE -> Forms("he", "his", "is")
        else -> Forms("they", "their", "are")
    }
}

class SettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: SecretCipher
) {

    constructor(context: Context) : this(context.dataStore, KeystoreSecretCipher())

    private val secretCacheLock = Any()

    @Volatile
    private var cachedApiKey: CachedSecret? = null

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val API_KEY_ENCRYPTED = stringPreferencesKey("api_key_encrypted")
        val NAME = stringPreferencesKey("name")
        val LANGUAGE = stringPreferencesKey("language")
        val HABITS = stringPreferencesKey("habits")
        val INTERESTS = stringPreferencesKey("interests")
        val SESSION_MINUTES = intPreferencesKey("session_minutes")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val PRONOUNS = stringPreferencesKey("pronouns")
        val STREAMING_ENABLED = booleanPreferencesKey("streaming_enabled")
        val TONE = stringPreferencesKey("tone")
        val VOICE_PITCH = floatPreferencesKey("voice_pitch")
        val VOICE_RATE = floatPreferencesKey("voice_rate")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            apiKey = readApiKey(p),
            name = p[Keys.NAME] ?: "",
            language = p[Keys.LANGUAGE] ?: "en",
            habits = SettingsLists.split(p[Keys.HABITS]),
            interests = SettingsLists.split(p[Keys.INTERESTS]),
            sessionMinutes = p[Keys.SESSION_MINUTES] ?: 10,
            snoozeMinutes = p[Keys.SNOOZE_MINUTES] ?: 10,
            pronouns = p[Keys.PRONOUNS] ?: Pronouns.NEUTRAL,
            streamingEnabled = p[Keys.STREAMING_ENABLED] ?: false,
            tone = p[Keys.TONE] ?: Tones.UPBEAT,
            voicePitch = p[Keys.VOICE_PITCH] ?: 1.05f,
            voiceRate = p[Keys.VOICE_RATE] ?: 1.0f
        )
    }.flowOn(Dispatchers.IO)

    suspend fun update(
        apiKey: String? = null,
        name: String? = null,
        language: String? = null,
        habits: List<String>? = null,
        interests: List<String>? = null,
        sessionMinutes: Int? = null,
        snoozeMinutes: Int? = null,
        pronouns: String? = null,
        streamingEnabled: Boolean? = null,
        tone: String? = null,
        voicePitch: Float? = null,
        voiceRate: Float? = null
    ) {
        var writtenSecret: CachedSecret? = null
        dataStore.edit { p ->
            apiKey?.let { value ->
                val sanitized = sanitizeSecret(value)
                val encrypted = if (sanitized.isEmpty()) null else encryptWithRecovery(sanitized)
                if (encrypted == null) {
                    p.remove(Keys.API_KEY_ENCRYPTED)
                } else {
                    p[Keys.API_KEY_ENCRYPTED] = encrypted
                }
                p.remove(Keys.API_KEY)
                writtenSecret = CachedSecret(encrypted, legacy = null, plainText = sanitized)
            }
            name?.let { p[Keys.NAME] = it.trim() }
            language?.let { p[Keys.LANGUAGE] = it }
            habits?.let { p[Keys.HABITS] = SettingsLists.join(it) }
            interests?.let { p[Keys.INTERESTS] = SettingsLists.join(it) }
            sessionMinutes?.let { p[Keys.SESSION_MINUTES] = it }
            snoozeMinutes?.let { p[Keys.SNOOZE_MINUTES] = it }
            pronouns?.let { p[Keys.PRONOUNS] = it }
            streamingEnabled?.let { p[Keys.STREAMING_ENABLED] = it }
            tone?.let { p[Keys.TONE] = it }
            voicePitch?.let { p[Keys.VOICE_PITCH] = it }
            voiceRate?.let { p[Keys.VOICE_RATE] = it }
        }
        writtenSecret?.let { cachedApiKey = it }
    }

    /**
     * Migrates plaintext credentials and repairs unrecoverable Keystore state. A corrupt encrypted
     * value is replaced from the legacy value when possible; otherwise it is removed so the app
     * fails closed and lets the user enter a new credential.
     */
    suspend fun migrateLegacyApiKey() {
        var migratedSecret: CachedSecret? = null
        dataStore.edit { p ->
            val encrypted = p[Keys.API_KEY_ENCRYPTED]
            val legacy = sanitizeSecret(p[Keys.API_KEY].orEmpty())
            val decrypted = encrypted?.let(secretCipher::decrypt)

            when {
                decrypted != null -> {
                    p.remove(Keys.API_KEY)
                    migratedSecret = CachedSecret(encrypted, legacy = null, plainText = decrypted)
                }

                legacy.isNotEmpty() -> {
                    if (encrypted != null) runCatching(secretCipher::resetKey)
                    val replacement = encryptWithRecovery(legacy)
                    p[Keys.API_KEY_ENCRYPTED] = replacement
                    p.remove(Keys.API_KEY)
                    migratedSecret = CachedSecret(replacement, legacy = null, plainText = legacy)
                }

                encrypted != null -> {
                    runCatching(secretCipher::resetKey)
                    p.remove(Keys.API_KEY_ENCRYPTED)
                    p.remove(Keys.API_KEY)
                    migratedSecret = CachedSecret(null, legacy = null, plainText = "")
                }

                else -> {
                    p.remove(Keys.API_KEY)
                    migratedSecret = CachedSecret(null, legacy = null, plainText = "")
                }
            }
        }
        migratedSecret?.let { cachedApiKey = it }
    }

    private fun readApiKey(preferences: Preferences): String {
        val encrypted = preferences[Keys.API_KEY_ENCRYPTED]
        val legacy = preferences[Keys.API_KEY]
        cachedApiKey?.takeIf { it.matches(encrypted, legacy) }?.let { return it.plainText }

        return synchronized(secretCacheLock) {
            cachedApiKey?.takeIf { it.matches(encrypted, legacy) }?.plainText ?: run {
                val plainText = encrypted?.let(secretCipher::decrypt)
                    ?: legacy?.let(::sanitizeSecret)
                    ?: ""
                plainText.also {
                    cachedApiKey = CachedSecret(encrypted, legacy, plainText)
                }
            }
        }
    }

    private fun encryptWithRecovery(plainText: String): String = try {
        secretCipher.encrypt(plainText)
    } catch (firstFailure: Exception) {
        try {
            secretCipher.resetKey()
        } catch (resetFailure: Exception) {
            firstFailure.addSuppressed(resetFailure)
            throw firstFailure
        }
        try {
            secretCipher.encrypt(plainText)
        } catch (retryFailure: Exception) {
            retryFailure.addSuppressed(firstFailure)
            throw retryFailure
        }
    }

    private data class CachedSecret(
        val encrypted: String?,
        val legacy: String?,
        val plainText: String
    ) {
        fun matches(currentEncrypted: String?, currentLegacy: String?): Boolean =
            encrypted == currentEncrypted && legacy == currentLegacy
    }

    companion object {
        /** Trim whitespace/newlines and strip a leading UTF-8 BOM from pasted keys. */
        fun sanitizeSecret(value: String): String =
            value.trim().removePrefix("\uFEFF").trim()
    }
}
