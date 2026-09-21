package com.anaalarm.ui.alarm

/** Editable alarm fields compared against the loaded row to detect unsaved changes. */
internal data class AlarmDraft(
    val hour: Int,
    val minute: Int,
    val days: Int,
    val snoozeMinutes: Int,
    val enabled: Boolean,
    val challengeType: Int,
    val maxSnoozes: Int,
    val ringtoneUri: String?
)

internal fun alarmDraftChanged(saved: AlarmDraft, edited: AlarmDraft): Boolean = saved != edited
