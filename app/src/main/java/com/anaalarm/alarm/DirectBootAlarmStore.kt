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
    val enabledForRearm: Boolean = true,
    /** Snoozes already taken this firing; survives reboots and pre-unlock snoozes. */
    val snoozeCount: Int = 0,
    /** Maximum snoozes per firing; 0 means unlimited. */
    val maxSnoozes: Int = 0,
    /** Custom alarm sound reference; null keeps the system default. */
    val ringtoneUri: String? = null
) {
    init {
        require(id >= 0L) { "alarm id must be non-negative" }
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }
        require(days in 0..0b1111111) { "repeat mask out of range: $days" }
        require(snoozeMinutes > 0) { "snooze duration must be positive" }
        require(enabledForRearm || days == 0) { "only a one-shot may be retired" }
        require(snoozeCount >= 0) { "snooze count must be non-negative" }
        require(maxSnoozes >= 0) { "max snoozes must be non-negative" }
    }
}

internal class DirectBootAlarmStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) {

    private val preferences: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

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

    /** Writes one enabled snapshot, preserving any in-flight snooze count for the same id. */
    fun upsert(alarm: DirectBootAlarm) = synchronized(preferences) {
        val existing = get(alarm.id)
        commitOrThrow(
            preferences.edit().putString(
                key(alarm.id),
                encode(
                    alarm.copy(
                        enabledForRearm = true,
                        // schedule()/reconcile rewrites must not erase an active snooze chain;
                        // only the explicit post-fire reset zeroes the counter.
                        snoozeCount = existing?.snoozeCount ?: alarm.snoozeCount
                    )
                )
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
     * Makes the device-protected enabled set exactly match Room after unlock, preserving
     * in-flight snooze counters for ids that remain enabled. Retired snapshots are
     * intentionally preserved until Room retirement is acknowledged.
     */
    fun replaceEnabled(alarms: Collection<DirectBootAlarm>) = synchronized(preferences) {
        val existing = allLocked().associateBy { it.id }
        val enabled = alarms.associateBy { it.id }
        val editor = preferences.edit()
        allLocked()
            .filter { it.enabledForRearm && it.id !in enabled }
            .forEach { editor.remove(key(it.id)) }
        enabled.values.forEach { alarm ->
            editor.putString(
                key(alarm.id),
                encode(
                    alarm.copy(
                        enabledForRearm = true,
                        snoozeCount = existing[alarm.id]?.snoozeCount ?: alarm.snoozeCount
                    )
                )
            )
        }
        commitOrThrow(editor)
    }

    /** Adds one snooze to the counter; returns the updated snapshot or null when unknown. */
    fun incrementSnoozeCount(alarmId: Long): DirectBootAlarm? = synchronized(preferences) {
        val current = get(alarmId) ?: return null
        val updated = current.copy(snoozeCount = current.snoozeCount + 1)
        commitOrThrow(preferences.edit().putString(key(alarmId), encode(updated)))
        updated
    }

    /** Clears the snooze counter after a fresh regular delivery of the alarm. */
    fun resetSnoozeCount(alarmId: Long): DirectBootAlarm? = synchronized(preferences) {
        val current = get(alarmId) ?: return null
        if (current.snoozeCount == 0) return current
        val updated = current.copy(snoozeCount = 0)
        commitOrThrow(preferences.edit().putString(key(alarmId), encode(updated)))
        updated
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
        /** v2 adds the snooze counter and custom ringtone reference. */
        private const val FORMAT_VERSION = "v2"
        private const val LEGACY_FORMAT_VERSION = "v1"
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
            if (alarm.enabledForRearm) ENABLED else RETIRED,
            alarm.snoozeCount,
            alarm.maxSnoozes,
            alarm.ringtoneUri.orEmpty()
        ).joinToString("|")

        fun decode(encoded: String): DirectBootAlarm? = runCatching {
            val parts = encoded.split('|')
            when {
                parts.size == 10 && parts[0] == FORMAT_VERSION -> DirectBootAlarm(
                    id = parts[1].toLong(),
                    hour = parts[2].toInt(),
                    minute = parts[3].toInt(),
                    days = parts[4].toInt(),
                    snoozeMinutes = parts[5].toInt(),
                    enabledForRearm = decodeState(parts[6]),
                    snoozeCount = parts[7].toInt().also { require(it >= 0) },
                    maxSnoozes = parts[8].toInt().also { require(it >= 0) },
                    ringtoneUri = parts[9].takeIf { it.isNotEmpty() }
                )
                // Upgrades from the v1 mirror keep working; legacy snapshots simply have no
                // snooze counter, cap, or custom ringtone yet.
                parts.size == 7 && parts[0] == LEGACY_FORMAT_VERSION -> DirectBootAlarm(
                    id = parts[1].toLong(),
                    hour = parts[2].toInt(),
                    minute = parts[3].toInt(),
                    days = parts[4].toInt(),
                    snoozeMinutes = parts[5].toInt(),
                    enabledForRearm = decodeState(parts[6])
                )
                else -> error("unknown direct-boot alarm format")
            }
        }.getOrNull()

        private fun decodeState(raw: String): Boolean = when (raw) {
            ENABLED -> true
            RETIRED -> false
            else -> error("unknown direct-boot alarm state")
        }
    }
}
