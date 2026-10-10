package com.anaalarm.voice

internal interface SpeechOutput : TurnSpeaker {
    var onFocusLost: (() -> Unit)?
    val isReady: Boolean
    suspend fun awaitReady(timeoutMs: Long = 5_000L): Boolean
    fun setLanguage(language: String)
    fun setPitch(pitch: Float)
    fun setSpeechRate(rate: Float)
    fun speak(text: String, onStart: () -> Unit = {}, completion: () -> Unit = {})
    fun stop()
    fun shutdown()
}
