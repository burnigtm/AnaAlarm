package com.anaalarm.alarm

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal alarm state that is safe to read before the user's first unlock after reboot.
 *
 * The store lives exclusively in device-protected storage. It deliberately contains no profile,
 * API credential, transcript, prompt, or other conversational data. A retired one-shot is kept as
 * a tombstoned snapshot until unlock so it cannot be re-armed, while its snooze duration remains
 * available if the user snoozes (including snoozing an already-fired snooze) before unlocking.
 */
internal data class DirectBootAlarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val snoozeMinutes: Int,
    val enabledForRearm: Boolean = true
) {
    init {
        require(id >= 0L) { "alarm id must be non-negative" }
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }
        require(days in 0..0b1111111) { "repeat mask out of range: $days" }
        require(snoozeMinutes > 0) { "snooze duration must be positive" }
        require(enabledForRearm || days == 0) { "only a one-shot may be retired" }
    }
}

internal class DirectBootAlarmStore(context: Context) {

    private val preferences: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun get(alarmId: Long): DirectBootAlarm? = synchronized(preferences) {
        val storageKey = key(alarmId)
        val raw = preferences.all[storageKey]
        val decoded = (raw as? String)?.let(::decode)
        if (decoded?.id == alarmId) {
            decoded
        } else {
            // A wrong value type, invalid payload, or key/payload id mismatch is corrupt state.
            // Remove it durably so every later locked-boot pass observes the same safe result.
            if (raw != null) commitOrThrow(preferences.edit().remove(storageKey))
            null
        }
    }

    fun enabledAlarms(): List<DirectBootAlarm> = synchronized(preferences) {
        allLocked().filter { it.enabledForRearm }.sortedBy { it.id }
    }

    fun retiredOneShots(): List<DirectBootAlarm> = synchronized(preferences) {
        allLocked().filterNot { it.enabledForRearm }.sortedBy { it.id }
    }

    /** Writes one enabled snapshot and clears any older retirement marker for the same id. */
    fun upsert(alarm: DirectBootAlarm) = synchronized(preferences) {
        commitOrThrow(
            preferences.edit().putString(
                key(alarm.id),
                encode(alarm.copy(enabledForRearm = true))
            )
        )
    }

    fun remove(alarmId: Long) = synchronized(preferences) {
        commitOrThrow(preferences.edit().remove(key(alarmId)))
    }

    /** Atomically prevents future boot re-arming while retaining the minimal snooze snapshot. */
    fun retireOneShot(alarmId: Long): DirectBootAlarm? = synchronized(preferences) {
        val existing = get(alarmId) ?: return null
        val retired = existing.copy(enabledForRearm = false)
        commitOrThrow(preferences.edit().putString(key(alarmId), encode(retired)))
        retired
    }

    /**
     * Makes the device-protected enabled set exactly match Room after unlock.
     * Retired snapshots are intentionally preserved until Room retirement is acknowledged.
     */
    fun replaceEnabled(alarms: Collection<DirectBootAlarm>) = synchronized(preferences) {
        val enabled = alarms.associateBy { it.id }
        val editor = preferences.edit()
        allLocked()
            .filter { it.enabledForRearm && it.id !in enabled }
            .forEach { editor.remove(key(it.id)) }
        enabled.values.forEach { alarm ->
            editor.putString(key(alarm.id), encode(alarm.copy(enabledForRearm = true)))
        }
        commitOrThrow(editor)
    }

    internal fun clearForTest() = synchronized(preferences) {
        commitOrThrow(preferences.edit().clear())
    }

    private fun allLocked(): List<DirectBootAlarm> {
        val corruptKeys = mutableListOf<String>()
        val alarms = preferences.all
            .asSequence()
            .filter { (name, _) -> name.startsWith(ALARM_KEY_PREFIX) }
            .mapNotNull { (name, value) ->
                val expectedId = name.removePrefix(ALARM_KEY_PREFIX).toLongOrNull()
                val decoded = (value as? String)?.let(::decode)
                if (expectedId != null && decoded?.id == expectedId) {
                    decoded
                } else {
                    corruptKeys += name
                    null
                }
            }
            .toList()
        if (corruptKeys.isNotEmpty()) {
            val editor = preferences.edit()
            corruptKeys.forEach { editor.remove(it) }
            commitOrThrow(editor)
        }
        return alarms
    }

    private fun commitOrThrow(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Could not persist the direct-boot alarm mirror" }
    }

    internal companion object {
        internal const val PREFERENCES_NAME = "direct_boot_alarms"
        private const val ALARM_KEY_PREFIX = "alarm."
        private const val FORMAT_VERSION = "v1"
        private const val ENABLED = "enabled"
        private const val RETIRED = "retired"

        private fun key(alarmId: Long): String = "$ALARM_KEY_PREFIX$alarmId"

        fun encode(alarm: DirectBootAlarm): String = listOf(
            FORMAT_VERSION,
            alarm.id,
            alarm.hour,
            alarm.minute,
            alarm.days,
            alarm.snoozeMinutes,
            if (alarm.enabledForRearm) ENABLED else RETIRED
        ).joinToString("|")

        fun decode(encoded: String): DirectBootAlarm? = runCatching {
            val parts = encoded.split('|')
            require(parts.size == 7 && parts[0] == FORMAT_VERSION)
            DirectBootAlarm(
                id = parts[1].toLong(),
                hour = parts[2].toInt(),
                minute = parts[3].toInt(),
                days = parts[4].toInt(),
                snoozeMinutes = parts[5].toInt(),
                enabledForRearm = when (parts[6]) {
                    ENABLED -> true
                    RETIRED -> false
                    else -> error("unknown direct-boot alarm state")
                }
            )
        }.getOrNull()
    }
}
