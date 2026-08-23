package com.anaalarm.support

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.anaalarm.AnaAlarmApp
import com.anaalarm.data.AlarmEntity
import com.anaalarm.data.AnaDatabase
import com.anaalarm.data.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Shared access to the app under test. Instrumented tests run inside the real application
 * process, so every helper here touches the same singletons the shipped app uses.
 */
object TestEnv {

    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    val app: AnaAlarmApp
        get() = context.applicationContext as AnaAlarmApp

    val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * AlarmManager outlives the database, so cancelling only what is stored leaves earlier
     * test alarms armed and hides later ones behind them in `nextAlarmClock`. Room autoincrement
     * also survives `clearAllTables`, so ids walk past any small hardcoded window.
     */
    private val scheduledIdSpace: List<Long> = ((0L..500L) + (4000L..4100L)).toList()

    fun cancelAllScheduledAlarms() {
        runBlocking {
            runCatching {
                app.memoryStore.alarms.first().forEach { app.alarmScheduler.cancel(it) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { alarmManager.cancelAll() }
        }
        scheduledIdSpace.forEach { id ->
            runCatching { app.alarmScheduler.cancel(AlarmEntity(id = id, hour = 0, minute = 0)) }
        }
    }

    /** Cancels every scheduled alarm and wipes all Room tables. */
    fun clearDatabase() {
        cancelAllScheduledAlarms()
        val db = AnaDatabase.get(context)
        db.clearAllTables()
        // Autoincrement is not a table Room clears; reset it so later tests reuse low ids.
        runCatching { db.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence") }
    }

    /** Restores factory settings so tests never inherit another test's configuration. */
    fun resetSettings() = runBlocking {
        val defaults = AppSettings()
        app.settingsStore.update(
            apiKey = defaults.apiKey,
            name = defaults.name,
            language = defaults.language,
            habits = defaults.habits,
            interests = defaults.interests,
            sessionMinutes = defaults.sessionMinutes,
            snoozeMinutes = defaults.snoozeMinutes,
            pronouns = defaults.pronouns,
            streamingEnabled = defaults.streamingEnabled,
            tone = defaults.tone,
            voicePitch = defaults.voicePitch,
            voiceRate = defaults.voiceRate,
            avatar = defaults.avatar
        )
    }

    fun settings(): AppSettings = runBlocking { app.settingsStore.settings.first() }

    fun resetAll() {
        clearDatabase()
        resetSettings()
        app.overrideAiBackend(null)
    }

    /**
     * Polls [probe] until it returns a non-null value. AlarmManager and the application
     * coroutine scope both settle asynchronously, so tests must wait rather than assert once.
     */
    fun <T : Any> pollFor(
        timeoutMs: Long = 8_000,
        intervalMs: Long = 100,
        probe: () -> T?
    ): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(intervalMs)
        }
        return probe()
    }

    fun waitUntil(timeoutMs: Long = 8_000, intervalMs: Long = 100, condition: () -> Boolean): Boolean =
        pollFor(timeoutMs, intervalMs) { if (condition()) true else null } == true
}
