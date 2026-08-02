package com.anaalarm.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechListener(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null

    var onResult: ((String) -> Unit)? = null
    var onError: ((Int) -> Unit)? = null
    var onStarted: (() -> Unit)? = null

    @Volatile
    var isListening = false
        private set

    @Volatile
    var lastErrorCode: Int? = null
        private set

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "onReadyForSpeech")
        }
        override fun onBeginningOfSpeech() {
            Log.i(TAG, "onBeginningOfSpeech")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            Log.i(TAG, "onEndOfSpeech")
        }
        override fun onError(error: Int) {
            Log.w(TAG, "onError code=$error (${errorName(error)})")
            isListening = false
            lastErrorCode = error
            mainHandler.post { onError?.invoke(error) }
        }
        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull()?.trim().orEmpty()
            Log.i(TAG, "onResults best='$best' all=$matches")
            mainHandler.post {
                if (best.isNotEmpty()) {
                    lastErrorCode = null
                    onResult?.invoke(best)
                } else {
                    lastErrorCode = SpeechRecognizer.ERROR_NO_MATCH
                    onError?.invoke(SpeechRecognizer.ERROR_NO_MATCH)
                }
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun startListening(languageTag: String) {
        if (isListening) return
        if (!isAvailable) {
            Log.e(TAG, "SpeechRecognizer not available")
            lastErrorCode = SpeechRecognizer.ERROR_CLIENT
            mainHandler.post { onError?.invoke(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startOnMain(languageTag)
        } else {
            mainHandler.post { startOnMain(languageTag) }
        }
    }

    private fun startOnMain(languageTag: String) {
        val engine = ensureRecognizer()
        if (engine == null) {
            lastErrorCode = SpeechRecognizer.ERROR_CLIENT
            onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_200L)
        }
        runCatching {
            engine.setRecognitionListener(listener)
            engine.startListening(intent)
            isListening = true
            Log.i(TAG, "startListening lang=$languageTag")
            onStarted?.invoke()
        }.onFailure {
            Log.e(TAG, "startListening failed", it)
            isListening = false
            lastErrorCode = SpeechRecognizer.ERROR_CLIENT
            onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun stopListening() {
        val stop = {
            runCatching {
                if (isListening) recognizer?.stopListening()
                recognizer?.cancel()
            }
            isListening = false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) stop()
        else mainHandler.post(stop)
    }

    fun destroy() {
        mainHandler.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
            isListening = false
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (recognizer != null) return recognizer
        recognizer = runCatching {
            // IMPORTANT: do NOT bind to com.google.android.tts — that is a TTS engine,
            // not ASR, and fails with language-pack errors.
            val asr = findSpeechRecognizerComponent()
            if (asr != null) {
                Log.i(TAG, "Using ASR recognizer: $asr")
                SpeechRecognizer.createSpeechRecognizer(appContext, asr)
            } else {
                Log.i(TAG, "Using default SpeechRecognizer")
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        }.onFailure {
            Log.e(TAG, "createSpeechRecognizer failed", it)
        }.getOrNull()
        return recognizer
    }

    private fun findSpeechRecognizerComponent(): ComponentName? {
        val pm = appContext.packageManager
        val services = pm.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            PackageManager.MATCH_ALL
        )
        fun pkg(info: android.content.pm.ResolveInfo) =
            info.serviceInfo?.packageName.orEmpty()

        val ranked = services.sortedBy { info ->
            val p = pkg(info).lowercase()
            when {
                p.contains("tts") -> 100 // never prefer TTS packages
                p.contains("googlequicksearchbox") -> 0
                p.contains("soundsearch") -> 1
                p.contains("speechservices") -> 2
                p == "com.google.android.as" -> 3
                p.contains("google") && !p.contains("tts") -> 4
                else -> 50
            }
        }
        val best = ranked.firstOrNull { !pkg(it).contains("tts", ignoreCase = true) }
            ?: return null
        val si = best.serviceInfo ?: return null
        return ComponentName(si.packageName, si.name)
    }

    companion object {
        private const val TAG = "AnaSpeech"

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "UNKNOWN($code)"
        }
    }
}
