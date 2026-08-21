package com.anaalarm.alarm

import android.app.AlarmManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.anaalarm.AnaAlarmApp
import com.anaalarm.data.AlarmEntity
import com.anaalarm.ui.wakeup.WakeUpActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

sealed interface AlarmScheduleResult {
    data class Scheduled(
        val triggerAt: LocalDateTime,
        val triggerAtMillis: Long
    ) : AlarmScheduleResult

    /** The stored alarm is disabled, so any old system alarm was removed. */
    data object Cancelled : AlarmScheduleResult

    data class Failed(
        val reason: Reason,
        val cause: Throwable? = null
    ) : AlarmScheduleResult {
        enum class Reason {
            EXACT_ALARM_PERMISSION_REQUIRED,
            ALARM_NOT_FOUND,
            INVALID_ALARM,
            SNOOZE_LIMIT_REACHED,
            SYSTEM_ERROR
        }
    }
}

sealed interface FiredAlarmResult {
    data object OneShotDisabled : FiredAlarmResult
    data class RepeatingRearmed(val result: AlarmScheduleResult) : FiredAlarmResult
    data object AlarmNotFound : FiredAlarmResult
}

class AlarmScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager,
    directBootPreferencesName: String = DirectBootAlarmStore.PREFERENCES_NAME
) {

    private val directBootStore = DirectBootAlarmStore(context, directBootPreferencesName)

    fun canScheduleExact(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true

    fun nextFireTime(hour: Int, minute: Int, days: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime =
        AlarmTriggerCalculator.nextTriggerTime(hour, minute, days, now)

    /**
     * Makes the system alarm state match [alarm] and reports the real outcome.
     *
     * This method deliberately never throws. UI and re-arming callers must be able to
     * distinguish a registered alarm from a permission denial or framework failure.
     */
    fun schedule(alarm: AlarmEntity): AlarmScheduleResult {
        if (!alarm.enabled) {
            return runCatching { cancel(alarm) }.fold(
                onSuccess = { AlarmScheduleResult.Cancelled },
                onFailure = {
                    AlarmScheduleResult.Failed(
                        AlarmScheduleResult.Failed.Reason.SYSTEM_ERROR,
                        it
                    )
                }
            )
        }

        val next = try {
            nextFireTime(alarm.hour, alarm.minute, alarm.days)
        } catch (error: IllegalArgumentException) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.INVALID_ALARM,
                error
            )
        }
        val directBootAlarm = try {
            alarm.toDirectBootAlarm()
        } catch (error: IllegalArgumentException) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.INVALID_ALARM,
                error
            )
        }
        try {
            // Mirror desired enabled state even when exact-alarm permission is currently absent;
            // a later permission grant or locked boot can reconcile it without credential storage.
            directBootStore.upsert(directBootAlarm)
        } catch (error: RuntimeException) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.SYSTEM_ERROR,
                error
            )
        }

        if (!canScheduleExact()) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.EXACT_ALARM_PERMISSION_REQUIRED
            )
        }
        return registerExactAlarm(alarm.id, next) { pendingIntent(alarm.id) }
    }

    fun cancel(alarm: AlarmEntity) {
        directBootStore.remove(alarm.id)
        cancelById(alarm.id)
    }

    /** Cancels only the regular delivery path, preserving a newly requested snooze. */
    internal fun cancelRegular(alarm: AlarmEntity) {
        cancelRegular(alarm.id)
    }

    /** Schedules a fresh exact wake-up using the alarm's persisted snooze duration. */
    suspend fun scheduleSnooze(alarmId: Long): AlarmScheduleResult {
        val app = context.applicationContext as AnaAlarmApp
        val alarm = app.memoryStore.getAlarm(alarmId)
            ?: return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.ALARM_NOT_FOUND
            )
        return scheduleSnooze(alarm)
    }

    internal fun scheduleSnooze(
        alarm: AlarmEntity,
        now: LocalDateTime = LocalDateTime.now()
    ): AlarmScheduleResult {
        if (alarm.snoozeMinutes <= 0) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.INVALID_ALARM
            )
        }
        if (!canSnooze(alarm.id)) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.SNOOZE_LIMIT_REACHED
            )
        }
        if (!canScheduleExact()) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.EXACT_ALARM_PERMISSION_REQUIRED
            )
        }
        val triggerAt = now.plusMinutes(alarm.snoozeMinutes.toLong())
        val result = registerExactAlarm(alarm.id, triggerAt) { snoozePendingIntent(alarm.id) }
        // The counter lives in the device-protected mirror so caps survive reboots and are
        // enforced identically before and after first unlock.
        if (result is AlarmScheduleResult.Scheduled) {
            runCatching { directBootStore.incrementSnoozeCount(alarm.id) }
                .onFailure { Log.w(TAG, "Snooze counter update failed for ${alarm.id}", it) }
        }
        return result
    }

    /** Snoozes using only the device-protected snapshot; safe before first unlock. */
    internal fun scheduleDirectBootSnooze(
        alarmId: Long,
        now: LocalDateTime = LocalDateTime.now()
    ): AlarmScheduleResult {
        val alarm = directBootStore.get(alarmId)
            ?: return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.ALARM_NOT_FOUND
            )
        if (alarm.snoozeMinutes <= 0) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.INVALID_ALARM
            )
        }
        if (!canSnooze(alarmId)) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.SNOOZE_LIMIT_REACHED
            )
        }
        if (!canScheduleExact()) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.EXACT_ALARM_PERMISSION_REQUIRED
            )
        }
        val triggerAt = now.plusMinutes(alarm.snoozeMinutes.toLong())
        val result = registerExactAlarm(alarmId, triggerAt) { snoozePendingIntent(alarmId) }
        if (result is AlarmScheduleResult.Scheduled) {
            runCatching { directBootStore.incrementSnoozeCount(alarmId) }
                .onFailure { Log.w(TAG, "Snooze counter update failed for $alarmId", it) }
        }
        return result
    }

    /** True while the alarm's per-firing snooze cap allows another snooze (0 = unlimited). */
    fun canSnooze(alarmId: Long): Boolean {
        val snapshot = directBootStore.get(alarmId) ?: return true
        return snapshot.maxSnoozes <= 0 || snapshot.snoozeCount < snapshot.maxSnoozes
    }

    internal fun directBootSnoozeMinutes(alarmId: Long): Int? =
        directBootStore.get(alarmId)?.snoozeMinutes

    fun rescheduleNext(alarmId: Long): Job {
        val app = context.applicationContext as AnaAlarmApp
        return app.applicationScope.launch { runCatching { rescheduleNextNow(alarmId) } }
    }

    fun rescheduleAll(): Job {
        val app = context.applicationContext as AnaAlarmApp
        return app.applicationScope.launch { runCatching { reconcileUnlockedNow() } }
    }

    suspend fun rescheduleNextNow(alarmId: Long): AlarmScheduleResult? {
        val app = context.applicationContext as AnaAlarmApp
        val alarm = app.memoryStore.getAlarm(alarmId)
        if (alarm == null) {
            directBootStore.remove(alarmId)
            cancelById(alarmId)
            return null
        }
        return schedule(alarm)
    }

    suspend fun rescheduleAllNow(): List<AlarmScheduleResult> = reconcileUnlockedNow()

    /**
     * Applies locked-boot one-shot retirements to Room, then makes the device-protected mirror and
     * AlarmManager registrations exactly match current enabled credential-protected rows.
     */
    suspend fun reconcileUnlockedNow(): List<AlarmScheduleResult> {
        val app = context.applicationContext as AnaAlarmApp
        directBootStore.retiredOneShots().forEach { retired ->
            val alarm = app.memoryStore.getAlarm(retired.id)
            if (alarm != null && alarm.days == 0 && alarm.enabled) {
                app.memoryStore.setAlarmEnabled(retired.id, false)
            }
            // Remove only after the credential-side retirement succeeds. A crash or Room failure
            // leaves the tombstone available for the next USER_UNLOCKED/BOOT_COMPLETED pass.
            directBootStore.remove(retired.id)
        }

        val previouslyMirroredIds = directBootStore.enabledAlarms().mapTo(mutableSetOf()) { it.id }
        val enabled = app.memoryStore.getEnabledAlarms()
        val mirrorable = enabled.mapNotNull { alarm ->
            runCatching { alarm.toDirectBootAlarm() }.getOrNull()
        }
        directBootStore.replaceEnabled(mirrorable)
        val desiredIds = mirrorable.mapTo(mutableSetOf()) { it.id }
        // replaceEnabled removes stale durable state, but AlarmManager registrations are a
        // separate store. Cancel ids that disappeared from Room (or became invalid) so unlock
        // reconciliation is a true three-way reconciliation rather than a mirror-only cleanup.
        (previouslyMirroredIds - desiredIds).forEach(::cancelById)
        return enabled.map { schedule(it) }
    }

    /** Re-arms only device-protected enabled snapshots and never touches Room/DataStore. */
    fun rescheduleDirectBootNow(): List<AlarmScheduleResult> =
        directBootStore.enabledAlarms().map(::scheduleDirectBoot)

    /**
     * Updates durable state after a delivered alarm. A zero repeat mask is a one-shot and is
     * disabled; repeating alarms are armed for their next selected weekday.
     */
    suspend fun handleFiredAlarm(alarmId: Long): FiredAlarmResult {
        val app = context.applicationContext as AnaAlarmApp
        val alarm = app.memoryStore.getAlarm(alarmId) ?: return FiredAlarmResult.AlarmNotFound
        // A fresh delivery starts a new snooze budget.
        runCatching { directBootStore.resetSnoozeCount(alarmId) }
        if (alarm.days == 0) {
            app.memoryStore.setAlarmEnabled(alarmId, false)
            directBootStore.remove(alarmId)
            // The user can tap Snooze while this receiver's async Room work is still running.
            // Do not erase that newly registered snooze PendingIntent here.
            cancelRegular(alarm)
            return FiredAlarmResult.OneShotDisabled
        }
        return FiredAlarmResult.RepeatingRearmed(schedule(alarm))
    }

    /**
     * Handles a regular alarm delivered before first unlock without initializing credential data.
     * Retired one-shots retain their snooze snapshot but are excluded from every boot re-arm.
     */
    fun handleDirectBootFiredAlarm(alarmId: Long): FiredAlarmResult {
        val alarm = directBootStore.get(alarmId)
            ?.takeIf { it.enabledForRearm }
            ?: return FiredAlarmResult.AlarmNotFound
        // A fresh delivery starts a new snooze budget (one-shots retire below anyway).
        runCatching { directBootStore.resetSnoozeCount(alarmId) }
        if (alarm.days == 0) {
            directBootStore.retireOneShot(alarmId)
            cancelRegular(alarmId)
            return FiredAlarmResult.OneShotDisabled
        }
        return FiredAlarmResult.RepeatingRearmed(scheduleDirectBoot(alarm))
    }

    internal fun directBootSnapshot(alarmId: Long): DirectBootAlarm? = directBootStore.get(alarmId)

    internal fun clearDirectBootStoreForTest() = directBootStore.clearForTest()

    private fun scheduleDirectBoot(alarm: DirectBootAlarm): AlarmScheduleResult {
        val next = try {
            nextFireTime(alarm.hour, alarm.minute, alarm.days)
        } catch (error: IllegalArgumentException) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.INVALID_ALARM,
                error
            )
        }
        if (!canScheduleExact()) {
            return AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.EXACT_ALARM_PERMISSION_REQUIRED
            )
        }
        return registerExactAlarm(alarm.id, next) { pendingIntent(alarm.id) }
    }

    private fun cancelById(alarmId: Long) {
        cancelRegular(alarmId)
        alarmManager.cancel(snoozePendingIntent(alarmId))
        cancelLegacyPendingIntents(alarmId)
        NextAlarmWidgetProvider.updateAll(context)
    }

    private fun cancelRegular(alarmId: Long) {
        alarmManager.cancel(pendingIntent(alarmId))
        alarmManager.cancel(showIntent(alarmId))
    }

    private fun pendingIntent(alarmId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_SCHEDULED_ALARM)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            context,
            requestCode(alarmId, REQUEST_KIND_REGULAR),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun snoozePendingIntent(alarmId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_SCHEDULED_ALARM)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            .putExtra(AlarmReceiver.EXTRA_IS_SNOOZE, true)
        return PendingIntent.getBroadcast(
            context,
            requestCode(alarmId, REQUEST_KIND_SNOOZE),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun registerExactAlarm(
        alarmId: Long,
        triggerAt: LocalDateTime,
        operation: () -> PendingIntent
    ): AlarmScheduleResult {
        val triggerAtMillis = triggerAt
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        // Versions before the private production/debug action split used different identities.
        // Remove those tokens during the first schedule after upgrade so they cannot linger.
        cancelLegacyPendingIntents(alarmId)
        // setAlarmClock is the alarm-clock API: stronger idle exemptions and better
        // full-screen / lock-screen wake behavior than setExactAndAllowWhileIdle alone.
        return try {
            val clockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(alarmId))
            alarmManager.setAlarmClock(clockInfo, operation())
            NextAlarmWidgetProvider.updateAll(context)
            AlarmScheduleResult.Scheduled(triggerAt, triggerAtMillis)
        } catch (error: SecurityException) {
            AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.EXACT_ALARM_PERMISSION_REQUIRED,
                error
            )
        } catch (error: RuntimeException) {
            AlarmScheduleResult.Failed(
                AlarmScheduleResult.Failed.Reason.SYSTEM_ERROR,
                error
            )
        }
    }

    /** Shown in the status-bar "next alarm" affordance; tapping opens the wake-up screen. */
    private fun showIntent(alarmId: Long): PendingIntent {
        val intent = Intent(context, WakeUpActivity::class.java)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val creatorOptions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        backgroundActivityStartMode()
                    )
                }.toBundle()
            } else null
        return PendingIntent.getActivity(
            context,
            requestCode(alarmId, REQUEST_KIND_SHOW),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions
        )
    }

    private fun requestCode(alarmId: Long, kind: Int): Int =
        (alarmId xor (alarmId ushr 32)).toInt() xor kind

    private fun cancelLegacyPendingIntents(alarmId: Long) {
        val immutableNoCreate = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            Intent(context, AlarmReceiver::class.java)
                .setAction(LEGACY_ACTION_FIRE_ALARM)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId),
            immutableNoCreate
        )?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
        PendingIntent.getActivity(
            context,
            alarmId.toInt() + LEGACY_SHOW_OFFSET,
            Intent(context, WakeUpActivity::class.java)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            immutableNoCreate
        )?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private companion object {
        private const val TAG = "AnaAlarm"
        const val REQUEST_KIND_REGULAR = 0x1000_0000
        const val REQUEST_KIND_SNOOZE = 0x2000_0000
        const val REQUEST_KIND_SHOW = 0x3000_0000
        const val LEGACY_ACTION_FIRE_ALARM = "com.anaalarm.action.FIRE_ALARM"
        const val LEGACY_SHOW_OFFSET = 50_000

        @SuppressLint("InlinedApi")
        @Suppress("DEPRECATION")
        fun backgroundActivityStartMode(): Int =
            if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
    }
}

private fun AlarmEntity.toDirectBootAlarm(): DirectBootAlarm = DirectBootAlarm(
    id = id,
    hour = hour,
    minute = minute,
    days = days,
    snoozeMinutes = snoozeMinutes,
    maxSnoozes = maxSnoozes,
    ringtoneUri = ringtoneUri
)
