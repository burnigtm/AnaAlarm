package com.anaalarm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.anaalarm.telemetry.LatencyBoundary
import com.anaalarm.telemetry.LatencyMetric
import com.anaalarm.telemetry.LatencyMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class TtsState { INITIALIZING, READY, FAILED, SHUTDOWN }

/**
 * Speaks on the media/TTS stream (STREAM_TTS aliases to STREAM_MUSIC on modern Android).
 *
 * A single active utterance owns its start/completion callbacks and watchdog. Engine callbacks are
 * matched by utterance ID, so a delayed callback from a flushed utterance cannot advance a newer
 * conversation turn.
 */
class TtsManager(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val state = MutableStateFlow(TtsState.INITIALIZING)
    private val utterances = UtteranceRegistry()
    private val requestGeneration = TtsRequestGeneration()

    private var pendingSpeak: SpeakRequest? = null
    private var focusRequest: AudioFocusRequest? = null
    private var initWatchdog: Runnable? = null
    private var raisedMusicVolume: RaisedVolume? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(appContext) { status ->
            mainHandler.post { handleInit(status) }
        }
        val timeout = Runnable {
            if (state.value == TtsState.INITIALIZING) {
                Log.e(TAG, "TTS engine initialization timed out")
                markFailed()
            }
        }
        initWatchdog = timeout
        mainHandler.postDelayed(timeout, INIT_TIMEOUT_MS)
    }

    val isReady: Boolean get() = state.value == TtsState.READY
    val isFailed: Boolean get() = state.value == TtsState.FAILED
    val currentState: TtsState get() = state.value

    suspend fun awaitReady(timeoutMs: Long = 10_000L): Boolean {
        if (state.value == TtsState.READY) return true
        if (state.value != TtsState.INITIALIZING) return false
        return withTimeoutOrNull(timeoutMs) {
            state.first { it != TtsState.INITIALIZING } == TtsState.READY
        } ?: false
    }

    fun setLanguage(languageCode: String) {
        val locale = when (languageCode) {
            "pt" -> Locale("pt", "BR")
            else -> Locale.US
        }
        runWhenReady { ensureLanguage(locale) }
    }

    fun setPitch(pitch: Float) {
        runWhenReady { tts.setPitch(pitch.coerceIn(0.5f, 2.0f)) }
    }

    fun setSpeechRate(rate: Float) {
        runWhenReady { tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f)) }
    }

    /**
     * Queues speech while the engine is initializing. [onStart] is invoked only after the engine
     * reports that this exact utterance started, which is the safe point to silence alarm audio.
     * [completion] is always called for accepted/failed speech, except when [stop] cancels it.
     */
    fun speak(
        text: String,
        onStart: () -> Unit = {},
        completion: () -> Unit = {}
    ) {
        val request = SpeakRequest(
            text = text.trim(),
            onStart = onStart,
            completion = completion,
            generation = requestGeneration.current(),
            requestId = requestSequence.incrementAndGet(),
            requestedAtNanos = LatencyMetrics.nowNanos()
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            acceptSpeak(request)
        } else {
            mainHandler.post { acceptSpeak(request) }
        }
    }

    fun stop() {
        // Invalidate before posting cleanup so a background-thread speak() already queued on the
        // main looper cannot start after Stop.
        requestGeneration.invalidate()
        val stop = {
            pendingSpeak?.let { recordTtsStart(it, "cancelled") }
            pendingSpeak = null
            val active = utterances.clear()
            active?.let {
                mainHandler.removeCallbacks(it.watchdog)
                if (!it.started) it.onStartMissing("cancelled")
            }
            if (::tts.isInitialized) runCatching { tts.stop() }
            abandonAudioFocus()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) stop() else mainHandler.post(stop)
    }

    fun shutdown() {
        requestGeneration.invalidate()
        val shutdown = shutdown@{
            if (state.value == TtsState.SHUTDOWN) return@shutdown
            cancelInitWatchdog()
            pendingSpeak?.let { recordTtsStart(it, "shutdown") }
            pendingSpeak = null
            val active = utterances.clear()
            active?.let {
                mainHandler.removeCallbacks(it.watchdog)
                if (!it.started) it.onStartMissing("shutdown")
            }
            if (::tts.isInitialized) {
                runCatching { tts.stop() }
                runCatching { tts.shutdown() }
            }
            abandonAudioFocus()
            state.value = TtsState.SHUTDOWN
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            shutdown()
        } else {
            mainHandler.post(shutdown)
        }
    }

    private fun handleInit(status: Int) {
        if (state.value != TtsState.INITIALIZING) return
        cancelInitWatchdog()
        if (status == TextToSpeech.SUCCESS) {
            Log.i(TAG, "TTS engine ready")
            wireListener()
            applySpeechAudioAttributes()
            ensureLanguage(Locale.US)
            state.value = TtsState.READY
            flushPending()
        } else {
            Log.e(TAG, "TTS engine init failed status=$status")
            markFailed()
        }
    }

    private fun markFailed() {
        if (state.value == TtsState.SHUTDOWN) return
        cancelInitWatchdog()
        state.value = TtsState.FAILED
        val pending = pendingSpeak
        pendingSpeak = null
        pending?.let {
            recordTtsStart(it, "engine_failed")
            mainHandler.post(it.completion)
        }
    }

    private fun acceptSpeak(request: SpeakRequest) {
        if (!requestGeneration.isCurrent(request.generation)) {
            recordTtsStart(request, "cancelled")
            return
        }
        if (request.text.isEmpty()) {
            request.completion.invoke()
            return
        }
        when (state.value) {
            TtsState.READY -> doSpeak(request)
            TtsState.INITIALIZING -> {
                Log.i(TAG, "TTS initializing - queueing (${request.text.length} chars)")
                val replaced = pendingSpeak
                pendingSpeak = request
                // QUEUE_FLUSH semantics: a newer request supersedes an older pending request, but
                // callers still receive completion and cannot deadlock waiting for it.
                replaced?.let {
                    recordTtsStart(it, "superseded")
                    it.completion.invoke()
                }
            }
            TtsState.FAILED, TtsState.SHUTDOWN -> {
                recordTtsStart(request, "unavailable")
                request.completion.invoke()
            }
        }
    }

    private fun flushPending() {
        val pending = pendingSpeak ?: return
        pendingSpeak = null
        doSpeak(pending)
    }

    private fun doSpeak(request: SpeakRequest) {
        if (!requestGeneration.isCurrent(request.generation)) {
            recordTtsStart(request, "cancelled")
            return
        }
        requestAudioFocus()
        ensureAudibleMusicVolume()
        val id = UUID.randomUUID().toString()
        val watchdog = Runnable {
            Log.w(TAG, "TTS watchdog fired; stopping timed-out audio id=$id")
            finishUtterance(id, stopAudio = true, noStartOutcome = "watchdog")
        }
        val previous = utterances.replace(
            UtteranceRegistry.Entry(
                id = id,
                onStart = { startedAudioAtNanos ->
                    recordTtsStart(request, "started", startedAudioAtNanos)
                    request.onStart.invoke()
                },
                onComplete = request.completion,
                watchdog = watchdog,
                onStartMissing = { outcome -> recordTtsStart(request, outcome) }
            )
        )
        previous?.let {
            mainHandler.removeCallbacks(it.watchdog)
            if (!it.started) it.onStartMissing("superseded")
            mainHandler.post(it.onComplete)
        }

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
            putString(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0")
        }
        Log.i(TAG, "speak len=${request.text.length} id=$id")
        val result = tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "tts.speak returned ERROR id=$id")
            finishUtterance(id, stopAudio = false, noStartOutcome = "enqueue_failed")
            return
        }

        val timeoutMs = (2_500L + request.text.length * 80L).coerceIn(4_000L, 45_000L)
        mainHandler.postDelayed(watchdog, timeoutMs)
    }

    private fun finishUtterance(id: String, stopAudio: Boolean, noStartOutcome: String) {
        val active = utterances.finish(id) ?: return
        mainHandler.removeCallbacks(active.watchdog)
        if (!active.started) active.onStartMissing(noStartOutcome)
        if (stopAudio) runCatching { tts.stop() }
        abandonAudioFocus()
        mainHandler.post(active.onComplete)
    }

    private fun wireListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val id = utteranceId ?: return
                val startedAudioAtNanos = LatencyMetrics.nowNanos()
                mainHandler.post {
                    val callback = utterances.markStarted(id) ?: return@post
                    Log.i(TAG, "onStart id=$id")
                    callback.invoke(startedAudioAtNanos)
                }
            }

            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                mainHandler.post {
                    Log.i(TAG, "onDone id=$id")
                    finishUtterance(
                        id,
                        stopAudio = false,
                        noStartOutcome = "completed_without_start"
                    )
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                val id = utteranceId ?: return
                mainHandler.post {
                    Log.i(TAG, "onStop id=$id interrupted=$interrupted")
                    finishUtterance(id, stopAudio = false, noStartOutcome = "stopped")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleError(utteranceId, null)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleError(utteranceId, errorCode)
            }

            private fun handleError(utteranceId: String?, errorCode: Int?) {
                val id = utteranceId ?: return
                mainHandler.post {
                    Log.e(TAG, "onError id=$id code=$errorCode")
                    finishUtterance(id, stopAudio = false, noStartOutcome = "engine_error")
                }
            }
        })
    }

    private fun runWhenReady(block: () -> Unit) {
        mainHandler.post {
            if (state.value == TtsState.READY) runCatching(block)
        }
    }

    private fun applySpeechAudioAttributes() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        tts.setAudioAttributes(attrs)
    }

    private fun ensureLanguage(preferred: Locale) {
        val result = tts.setLanguage(preferred)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "locale $preferred unsupported ($result); falling back to default")
            tts.language = Locale.getDefault()
            if (tts.language == null) tts.language = Locale.US
        } else {
            Log.i(TAG, "TTS language set to $preferred result=$result")
        }
    }

    private fun ensureAudibleMusicVolume() {
        runCatching {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (max > 0 && current < (max * 0.25f).toInt()) {
                val target = (max * 0.7f).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                raisedMusicVolume = RaisedVolume(original = current, raisedTo = target)
                Log.i(TAG, "Raised STREAM_MUSIC $current -> $target (max=$max)")
            }
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = req
            val granted = audioManager.requestAudioFocus(req)
            Log.d(TAG, "audioFocus granted=$granted")
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        raisedMusicVolume?.let { volume ->
            runCatching {
                // Preserve an adjustment the user made while Ana was speaking.
                if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == volume.raisedTo) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume.original, 0)
                }
            }
        }
        raisedMusicVolume = null
    }

    private fun cancelInitWatchdog() {
        initWatchdog?.let { mainHandler.removeCallbacks(it) }
        initWatchdog = null
    }

    private fun recordTtsStart(
        request: SpeakRequest,
        outcome: String,
        endedAtNanos: Long = LatencyMetrics.nowNanos()
    ) {
        if (request.text.isEmpty()) return
        request.startLatency.recordAt(
            endedAtNanos = endedAtNanos,
            outcome = outcome,
            "characters" to request.text.length,
            "request_id" to request.requestId
        )
    }

    private data class SpeakRequest(
        val text: String,
        val onStart: () -> Unit,
        val completion: () -> Unit,
        val generation: Long,
        val requestId: Long,
        val requestedAtNanos: Long,
        val startLatency: LatencyBoundary = LatencyBoundary(
            metric = LatencyMetric.TTS_REQUEST_TO_START,
            startedAtNanos = requestedAtNanos
        )
    )

    private data class RaisedVolume(val original: Int, val raisedTo: Int)

    companion object {
        private const val TAG = "AnaTts"
        private const val INIT_TIMEOUT_MS = 12_000L
        private val requestSequence = AtomicLong(0L)
    }
}

/** Rejects queued speech requests captured before the most recent stop/shutdown. */
internal class TtsRequestGeneration {
    private val value = AtomicLong(0L)

    fun current(): Long = value.get()
    fun invalidate() {
        value.incrementAndGet()
    }
    fun isCurrent(candidate: Long): Boolean = candidate == value.get()
}

/** Stores exactly one utterance and rejects callbacks carrying any older utterance ID. */
internal class UtteranceRegistry {
    data class Entry(
        val id: String,
        val onStart: (Long) -> Unit,
        val onComplete: () -> Unit,
        val watchdog: Runnable,
        val onStartMissing: (String) -> Unit = {},
        var started: Boolean = false
    )

    private var active: Entry? = null

    @Synchronized
    fun replace(entry: Entry): Entry? {
        val previous = active
        active = entry
        return previous
    }

    @Synchronized
    fun markStarted(id: String): ((Long) -> Unit)? {
        val entry = active?.takeIf { it.id == id && !it.started } ?: return null
        entry.started = true
        return entry.onStart
    }

    @Synchronized
    fun finish(id: String): Entry? {
        val entry = active?.takeIf { it.id == id } ?: return null
        active = null
        return entry
    }

    @Synchronized
    fun clear(): Entry? {
        val entry = active
        active = null
        return entry
    }
}
