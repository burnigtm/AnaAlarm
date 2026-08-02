package com.anaalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anaalarm.AnaAlarmApp
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return

        val app = context.applicationContext as? AnaAlarmApp ?: return
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                val directBoot = shouldUseDirectBootState(intent.action, app.isUserUnlocked())
                val results = if (directBoot) {
                    // LOCKED_BOOT_COMPLETED must remain completely independent of Room, DataStore,
                    // Keystore credentials, and the AI/voice stack.
                    app.alarmScheduler.rescheduleDirectBootNow()
                } else {
                    check(app.ensureCredentialStorage()) {
                        "Credential storage unavailable after ${intent.action}"
                    }
                    app.alarmScheduler.reconcileUnlockedNow()
                }
                Log.i(
                    TAG,
                    "Rescheduled ${results.size} alarms after ${intent.action} " +
                        "source=${if (directBoot) "device" else "credential"}"
                )
            } catch (error: Exception) {
                Log.e(TAG, "Could not reschedule alarms after ${intent.action}", error)
            } finally {
                // Framework-delivered broadcasts always install a PendingResult before
                // onReceive. Direct invocations (instrumentation, previews, or other in-process
                // callers) do not, so goAsync() legitimately returns null for those calls.
                pending?.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AnaAlarm"
        internal fun shouldUseDirectBootState(action: String?, userUnlocked: Boolean): Boolean =
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED || !userUnlocked

        private val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
    }
}
