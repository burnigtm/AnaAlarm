package com.anaalarm.ui.wakeup

import android.os.SystemClock

/** Time boundaries are explicit so tests can advance a virtual clock without sleeping. */
data class SessionTiming(
    val today: () -> java.time.LocalDate = java.time.LocalDate::now,
    val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    val microphoneSettleMs: Long = 175L,
    val listenTimeoutMs: Long = 25_000L,
    val retryMs: Long = 350L,
    val reactionMs: Long = 650L,
    val challengeSuccessMs: Long = 750L,
    val deadlineGraceMs: Long = 90_000L
)
