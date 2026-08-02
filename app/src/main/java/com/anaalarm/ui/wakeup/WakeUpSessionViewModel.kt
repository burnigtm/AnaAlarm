package com.anaalarm.ui.wakeup

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anaalarm.AnaAlarmApp

/** Owns the live wake session across rotation/fold/large-screen configuration changes. */
class WakeUpSessionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AnaAlarmApp

    var controller by mutableStateOf<SessionController?>(null)
        private set
    var finishRequested by mutableStateOf(false)
        private set

    val activeAlarmId: Long get() = activeAlarmIdValue
    private var activeAlarmIdValue = -1L

    fun ensureSession(alarmId: Long, voiceAvailable: Boolean) {
        if (controller != null) return
        install(alarmId, voiceAvailable)
    }

    fun replaceSession(alarmId: Long, voiceAvailable: Boolean) {
        if (controller != null && activeAlarmIdValue == alarmId) return
        controller?.dispose(keepAlarmFallback = true)
        install(alarmId, voiceAvailable)
    }

    private fun install(alarmId: Long, voiceAvailable: Boolean) {
        activeAlarmIdValue = alarmId
        finishRequested = false
        controller = SessionController(
            app = app,
            scope = viewModelScope,
            onFinished = { finishRequested = true },
            voiceAvailable = voiceAvailable,
            alarmId = alarmId
        ).also { it.start() }
    }

    override fun onCleared() {
        controller?.dispose()
        controller = null
        super.onCleared()
    }
}
