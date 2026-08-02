package com.anaalarm.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anaalarm.alarm.AlarmScheduler
import com.anaalarm.data.AlarmEntity
import com.anaalarm.data.MemoryStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val memory: MemoryStore,
    private val scheduler: AlarmScheduler
) : ViewModel() {

    val alarms: StateFlow<List<AlarmEntity>> = memory.alarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                scheduler.schedule(updated)
            } else {
                scheduler.cancel(updated)
            }
        }
    }
}
