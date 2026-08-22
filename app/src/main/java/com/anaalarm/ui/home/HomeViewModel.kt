package com.anaalarm.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anaalarm.alarm.AlarmScheduleResult
import com.anaalarm.alarm.AlarmScheduler
import com.anaalarm.alarm.AlarmTriggerCalculator
import com.anaalarm.data.AlarmEntity
import com.anaalarm.data.MemoryStore
import com.anaalarm.data.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

sealed interface HomeNotice {
    data class ScheduleFailed(val reason: AlarmScheduleResult.Failed.Reason) : HomeNotice
}

data class UpcomingAlarm(
    val hour: Int,
    val minute: Int,
    val triggerAt: LocalDateTime
)

class HomeViewModel(
    private val memory: MemoryStore,
    private val scheduler: AlarmScheduler,
    private val settingsStore: SettingsStore
) : ViewModel() {

    val alarms: StateFlow<List<AlarmEntity>> = memory.alarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val upcomingAlarm: StateFlow<UpcomingAlarm?> = alarms
        .combine(minuteTicker()) { list, _ -> upcoming(list, LocalDateTime.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _notices = MutableSharedFlow<HomeNotice>(extraBufferCapacity = 1)
    val notices = _notices.asSharedFlow()

    /** Today's durable conversation summary; null while there is nothing to recap yet. */
    private val _recap = MutableStateFlow<String?>(null)
    val recap: StateFlow<String?> = _recap.asStateFlow()

    /** Habit names already marked done today. */
    private val _habitsDone = MutableStateFlow<Set<String>>(emptySet())
    val habitsDone: StateFlow<Set<String>> = _habitsDone.asStateFlow()

    private val _streaks = MutableStateFlow<Map<String, Int>>(emptyMap())
    val streaks: StateFlow<Map<String, Int>> = _streaks.asStateFlow()

    private val _habitNames = MutableStateFlow<List<String>>(emptyList())
    val habitNames: StateFlow<List<String>> = _habitNames.asStateFlow()

    init {
        refreshRecap()
    }

    /** Reloads the recap card's inputs; cheap local queries, no network involved. */
    fun refreshRecap() {
        viewModelScope.launch {
            val today = LocalDate.now()
            _recap.value = memory.todaySummary().trim().ifBlank { null }
            _habitsDone.value = memory.habitsForDate(today)
                .filter { it.done }
                .map { it.name }
                .toSet()
            val names = runCatching {
                settingsStore.settings.first().habits
            }.getOrDefault(emptyList())
            _habitNames.value = names
            _streaks.value = runCatching {
                memory.habitStreaks(names, today)
            }.getOrDefault(emptyMap())
        }
    }

    fun toggleHabit(name: String) {
        viewModelScope.launch {
            val nowDone = name !in _habitsDone.value
            runCatching { memory.setHabitDone(name, LocalDate.now(), nowDone) }
            refreshRecap()
        }
    }

    fun deleteAlarm(alarm: AlarmEntity) {
        viewModelScope.launch {
            scheduler.cancel(alarm)
            memory.deleteAlarm(alarm.id)
        }
    }

    fun toggleAlarm(alarm: AlarmEntity) {
        viewModelScope.launch {
            val enabled = !alarm.enabled
            memory.setAlarmEnabled(alarm.id, enabled)
            val updated = memory.getAlarm(alarm.id) ?: alarm.copy(enabled = enabled)
            if (enabled) {
                val result = scheduler.schedule(updated)
                if (result is AlarmScheduleResult.Failed) {
                    // The switch must represent reality: an alarm that the OS rejected is not
                    // enabled. Keeping it disabled also prevents a misleading boot re-arm.
                    memory.setAlarmEnabled(alarm.id, false)
                    scheduler.cancel(updated)
                    _notices.emit(HomeNotice.ScheduleFailed(result.reason))
                }
            } else {
                scheduler.cancel(updated)
            }
        }
    }

    companion object {
        /**
         * Emits immediately and then once a minute so time-relative labels ("today", "tomorrow")
         * stay correct across midnight without waiting for an unrelated alarms-table change.
         */
        private fun minuteTicker() = flow {
            while (true) {
                emit(Unit)
                delay(60_000L)
            }
        }

        fun upcoming(alarms: List<AlarmEntity>, now: LocalDateTime): UpcomingAlarm? {
            return alarms
                .filter { it.enabled }
                .map { alarm ->
                    alarm to AlarmTriggerCalculator.nextTriggerTime(
                        alarm.hour,
                        alarm.minute,
                        alarm.days,
                        now
                    )
                }
                .minByOrNull { it.second }
                ?.let { (alarm, triggerAt) ->
                    UpcomingAlarm(hour = alarm.hour, minute = alarm.minute, triggerAt = triggerAt)
                }
        }
    }
}
