package com.anaalarm.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.anaalarm.telemetry.LatencyMetric
import com.anaalarm.telemetry.LatencyMetrics
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin, lifecycle-safe wrapper around [SpeechRecognizer].
 *
 * Android may deliver callbacks after `cancel()` or even after a new recognition request has
 * started. Every request therefore owns a generation. Only callbacks from the current generation
 * can update state or reach the session controller.
 */
class SpeechListener internal constructor(
    context: Context,
    private val recognizerAvailabilityOverride: (() -> Boolean)? = null
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generations = RecognitionGeneration()

    private var recognizer: SpeechRecognizer? = null
    private var usingOnDeviceRecognizer = false
    private var forceDefaultRecognizer = false

    var onResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((Int) -> Unit)? = null
    var onStarted: (() -> Unit)? = null

    @Volatile
    var isListening = false
        private set

    @Volatile
    var lastErrorCode: Int? = null
        private set

    val isAvailable: Boolean
        get() = recognizerAvailabilityOverride?.let { availabilityCheck ->
            runCatching { availabilityCheck() }.getOrDefault(false)
        } ?: runCatching {
            val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            onDeviceAvailable || SpeechRecognizer.isRecognitionAvailable(appContext)
        }.getOrDefault(false)

    fun startListening(languageTag: String) {
        val generation = generations.next()
        val startedAtNanos = LatencyMetrics.nowNanos()
        isListening = false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startOnMain(languageTag, generation, startedAtNanos)
        } else {
            mainHandler.post { startOnMain(languageTag, generation, startedAtNanos) }
        }
    }

    private fun startOnMain(
        languageTag: String,
        generation: Long,
        startedAtNanos: Long
    ) {
        if (!generations.isCurrent(generation)) return
        if (!isAvailable) {
            Log.e(TAG, "SpeechRecognizer not available")
            deliverError(generation, SpeechRecognizer.ERROR_CLIENT, startedAtNanos, false)
            return
        }

        // Invalidate any work still queued inside the previous engine request before swapping its
        // listener. Its callbacks carry an older generation and are ignored.
        runCatching { recognizer?.cancel() }
        val engine = ensureRecognizer()
        if (engine == null) {
            deliverError(generation, SpeechRecognizer.ERROR_CLIENT, startedAtNanos, false)
            return
        }

        val intent = recognitionIntent(languageTag)
        val onDevice = usingOnDeviceRecognizer
        runCatching {
            engine.setRecognitionListener(
                recognitionListener(generation, languageTag, startedAtNanos, onDevice)
            )
            engine.startListening(intent)
            if (!generations.isCurrent(generation)) {
                engine.cancel()
                return
            }
            isListening = true
            lastErrorCode = null
            Log.i(
                TAG,
                "startListening generation=$generation lang=$languageTag onDevice=$usingOnDeviceRecognizer"
            )
            onStarted?.invoke()
        }.onFailure {
            Log.e(TAG, "startListening failed", it)
            deliverError(
                generation,
                SpeechRecognizer.ERROR_CLIENT,
                startedAtNanos,
                onDevice
            )
        }
    }

    private fun recognitionIntent(languageTag: String) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // These are endpointing hints; recognizer implementations may ignore them. Keeping the
            // values short makes conversational turns feel immediate without truncating normal
            // pauses between words.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 750L)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                700L
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
        }

    private fun recognitionListener(
        generation: Long,
        languageTag: String,
        startedAtNanos: Long,
        onDevice: Boolean
    ) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (generations.isCurrent(generation)) Log.i(TAG, "onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            if (generations.isCurrent(generation)) Log.i(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (generations.isCurrent(generation)) Log.i(TAG, "onEndOfSpeech")
        }

        override fun onError(error: Int) {
            if (!generations.isCurrent(generation)) return
            Log.w(TAG, "onError code=$error (${errorName(error)})")
            if (onDevice && isLanguageUnavailable(error)) {
                retryWithDefaultRecognizer(generation, languageTag, startedAtNanos)
            } else {
                deliverError(
                    generation,
                    error,
                    startedAtNanos,
                    onDevice,
                    LatencyMetrics.nowNanos()
                )
            }
        }

        override fun onResults(results: Bundle?) {
            val finalAtNanos = LatencyMetrics.nowNanos()
            val best = bestResult(results)
            if (best.isNotEmpty()) {
                Log.i(TAG, "onResults generation=$generation len=${best.length}")
                deliverResult(generation, best, startedAtNanos, onDevice, finalAtNanos)
            } else {
                deliverError(
                    generation,
                    SpeechRecognizer.ERROR_NO_MATCH,
                    startedAtNanos,
                    onDevice,
                    finalAtNanos
                )
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = bestResult(partialResults)
            if (partial.isEmpty() || !generations.isCurrent(generation)) return
            mainHandler.post {
                if (generations.isCurrent(generation)) onPartialResult?.invoke(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun retryWithDefaultRecognizer(
        generation: Long,
        languageTag: String,
        startedAtNanos: Long
    ) {
        mainHandler.post {
            if (!generations.isCurrent(generation)) return@post
            // The fallback is a new engine request within the same logical ASR measurement.
            // Advancing the generation makes any later callback from the retired on-device
            // recognizer stale while preserving the original latency start boundary.
            val retryGeneration = generations.next()
            Log.i(TAG, "On-device recognizer lacks $languageTag; retrying with default recognizer")
            runCatching { recognizer?.destroy() }
            recognizer = null
            usingOnDeviceRecognizer = false
            forceDefaultRecognizer = true
            isListening = false
            startOnMain(languageTag, retryGeneration, startedAtNanos)
        }
    }

    private fun deliverResult(
        generation: Long,
        text: String,
        startedAtNanos: Long,
        onDevice: Boolean,
        finalAtNanos: Long = LatencyMetrics.nowNanos()
    ) {
        mainHandler.post {
            if (!generations.complete(generation)) return@post
            isListening = false
            lastErrorCode = null
            LatencyMetrics.recordAt(
                metric = LatencyMetric.ASR_START_TO_FINAL,
                startedAtNanos = startedAtNanos,
                endedAtNanos = finalAtNanos,
                outcome = "result",
                "characters" to text.length,
                "generation" to generation,
                "recognizer" to if (onDevice) "on_device" else "default"
            )
            onResult?.invoke(text)
        }
    }

    private fun deliverError(
        generation: Long,
        error: Int,
        startedAtNanos: Long,
        onDevice: Boolean,
        finalAtNanos: Long = LatencyMetrics.nowNanos()
    ) {
        mainHandler.post {
            if (!generations.complete(generation)) return@post
            isListening = false
            lastErrorCode = error
            LatencyMetrics.recordAt(
                metric = LatencyMetric.ASR_START_TO_FINAL,
                startedAtNanos = startedAtNanos,
                endedAtNanos = finalAtNanos,
                outcome = "error",
                "error_code" to error,
                "generation" to generation,
                "recognizer" to if (onDevice) "on_device" else "default"
            )
            onError?.invoke(error)
        }
    }

    fun stopListening() {
        generations.invalidate()
        isListening = false
        val stop = {
            runCatching {
                recognizer?.stopListening()
                recognizer?.cancel()
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) stop() else mainHandler.post(stop)
    }

    fun destroy() {
        generations.invalidate()
        isListening = false
        onResult = null
        onPartialResult = null
        onError = null
        onStarted = null
        val destroy = {
            runCatching { recognizer?.destroy() }
            recognizer = null
            usingOnDeviceRecognizer = false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) destroy() else mainHandler.post(destroy)
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        recognizer = runCatching {
            if (
                !forceDefaultRecognizer &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            ) {
                usingOnDeviceRecognizer = true
                Log.i(TAG, "Using on-device SpeechRecognizer")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                usingOnDeviceRecognizer = false
                Log.i(TAG, "Using default SpeechRecognizer")
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        }.recoverCatching {
            usingOnDeviceRecognizer = false
            forceDefaultRecognizer = true
            Log.w(TAG, "On-device recognizer creation failed; using default", it)
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.onFailure {
            Log.e(TAG, "createSpeechRecognizer failed", it)
        }.getOrNull()
        return recognizer
    }

    private fun bestResult(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun isLanguageUnavailable(code: Int): Boolean =
        code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE

    companion object {
        private const val TAG = "AnaSpeech"
        // Added in API 31. Numeric values keep the class safe to load on API 26-30.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

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
            ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            else -> "UNKNOWN($code)"
        }
    }
}

/** Atomic generation gate used to reject callbacks from cancelled recognizer requests. */
internal class RecognitionGeneration {
    private val current = AtomicLong(0L)

    fun next(): Long = current.incrementAndGet()

    fun invalidate() {
        current.incrementAndGet()
    }

    fun isCurrent(generation: Long): Boolean = current.get() == generation

    fun complete(generation: Long): Boolean = current.compareAndSet(generation, generation + 1L)
}
