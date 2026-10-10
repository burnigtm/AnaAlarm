package com.anaalarm.voice

internal interface SpeechInput {
    val isAvailable: Boolean
    val isListening: Boolean
    var onResult: ((String) -> Unit)?
    var onPartialResult: ((String) -> Unit)?
    var onError: ((Int) -> Unit)?
    var onReady: (() -> Unit)?
    var onSpeechActivity: ((Boolean) -> Unit)?
    var onLevel: ((Float) -> Unit)?
    fun startListening(languageTag: String)
    fun stopListening()
    fun destroy()
}
