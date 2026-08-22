package com.anaalarm.data

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.Base64

/**
 * Device-local encrypted backup of everything except the API credential.
 *
 * The payload is plain JSON serialized, encrypted with a dedicated Android Keystore AES-GCM key
 * (separate alias from the API-key cipher so an export reset can never touch credentials), and
 * Base64-wrapped for SAF transfer. Restores merge by natural keys: daily logs replace per date,
 * habit marks replace per (name, date), sessions append.
 */
object DataExport {

    const val FORMAT = "anaalarm-export"
    const val VERSION = 1

    @Serializable
    data class ExportedSettings(
        val name: String,
        val language: String,
        val habits: List<String>,
        val interests: List<String>,
        val sessionMinutes: Int,
        val snoozeMinutes: Int,
        val pronouns: String,
        val tone: String,
        val voicePitch: Float,
        val voiceRate: Float
    )

    @Serializable
    data class ExportedLog(val date: String, val summary: String)

    @Serializable
    data class ExportedSession(
        val startedAt: Long,
        val endedAt: Long,
        val durationMs: Long,
        val turns: Int
    )

    @Serializable
    data class ExportedHabit(val name: String, val date: String, val done: Boolean)

    @Serializable
    data class Payload(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val exportedAtEpochMs: Long,
        val settings: ExportedSettings? = null,
        val dailyLogs: List<ExportedLog> = emptyList(),
        val sessions: List<ExportedSession> = emptyList(),
        val habits: List<ExportedHabit> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Collects the exportable snapshot. The API key is deliberately never included. */
    suspend fun build(memory: MemoryStore, settingsStore: SettingsStore): Payload {
        val settings = settingsStore.settings.first()
        return Payload(
            exportedAtEpochMs = System.currentTimeMillis(),
            settings = ExportedSettings(
                name = settings.name,
                language = settings.language,
                habits = settings.habits,
                interests = settings.interests,
                sessionMinutes = settings.sessionMinutes,
                snoozeMinutes = settings.snoozeMinutes,
                pronouns = settings.pronouns,
                tone = settings.tone,
                voicePitch = settings.voicePitch,
                voiceRate = settings.voiceRate
            ),
            dailyLogs = memory.exportableDailyLogs().map {
                ExportedLog(date = it.date, summary = it.summary)
            },
            sessions = memory.recentSessionRecords(limit = 365).map {
                ExportedSession(
                    startedAt = it.startedAt,
                    endedAt = it.endedAt,
                    durationMs = it.durationMs,
                    turns = it.turns
                )
            },
            habits = memory.recentHabitEvents(daysBack = 60).map {
                ExportedHabit(name = it.name, date = it.date, done = it.done)
            }
        )
    }

    fun serialize(payload: Payload): String =
        Base64.getEncoder().encodeToString(
            exportCipher().encrypt(json.encodeToString(Payload.serializer(), payload))
                .toByteArray(Charsets.UTF_8)
        )

    fun deserialize(text: String): Payload? = runCatching {
        val decrypted = exportCipher()
            .decrypt(String(Base64.getDecoder().decode(text.trim())))
            ?: return null
        val payload = json.decodeFromString(Payload.serializer(), decrypted)
        payload.takeIf { it.format == FORMAT && it.version == VERSION }
    }.getOrNull()

    /** Merges a payload into local stores; returns the number of restored rows. */
    suspend fun restore(payload: Payload, memory: MemoryStore, settingsStore: SettingsStore): Int {
        var restored = 0
        payload.settings?.let { s ->
            settingsStore.update(
                name = s.name,
                language = s.language,
                habits = s.habits,
                interests = s.interests,
                sessionMinutes = s.sessionMinutes,
                snoozeMinutes = s.snoozeMinutes,
                pronouns = s.pronouns,
                tone = s.tone,
                voicePitch = s.voicePitch,
                voiceRate = s.voiceRate
            )
            restored++
        }
        payload.dailyLogs.forEach { log ->
            runCatching {
                memory.saveDailyLog(summary = log.summary, date = LocalDate.parse(log.date))
                restored++
            }
        }
        payload.sessions.forEach { session ->
            runCatching {
                memory.recordSession(
                    startedAtMillis = session.startedAt,
                    endedAtMillis = session.endedAt,
                    turns = session.turns
                )
                restored++
            }
        }
        payload.habits.forEach { habit ->
            runCatching {
                memory.setHabitDone(habit.name, LocalDate.parse(habit.date), habit.done)
                restored++
            }
        }
        return restored
    }

    /**
     * Test seam: when set, replaces the Keystore-backed export cipher (unavailable under
     * Robolectric). Production always uses the real AndroidKeyStore cipher.
     */
    internal var cipherOverride: SecretCipher? = null

    private fun exportCipher(): SecretCipher =
        cipherOverride ?: KeystoreSecretCipher(keyAlias = "anaalarm.export.v1")
}
