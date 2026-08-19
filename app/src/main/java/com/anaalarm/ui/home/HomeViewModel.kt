package com.anaalarm.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anaalarm.alarm.AlarmScheduleResult
import com.anaalarm.alarm.AlarmScheduler
import com.anaalarm.alarm.AlarmTriggerCalculator
import com.anaalarm.data.AlarmEntity
import com.anaalarm.data.MemoryStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val scheduler: AlarmScheduler
) : ViewModel() {

    val alarms: StateFlow<List<AlarmEntity>> = memory.alarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val upcomingAlarm: StateFlow<UpcomingAlarm?> = alarms
        .map { upcoming(it, LocalDateTime.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _notices = MutableSharedFlow<HomeNotice>(extraBufferCapacity = 1)
    val notices = _notices.asSharedFlow()

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
