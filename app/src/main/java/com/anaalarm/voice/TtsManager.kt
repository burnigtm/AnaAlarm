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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Speaks on the media/TTS stream (STREAM_TTS aliases to STREAM_MUSIC on modern Android).
 * USAGE_ALARM was silent for many users because Google TTS audio is mixed on the music path.
 */
class TtsManager(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val ready = MutableStateFlow(false)
    private val onDone = AtomicReference<(() -> Unit)?>(null)
    private val pendingSpeak = AtomicReference<Pair<String, () -> Unit>?>(null)
    private var focusRequest: AudioFocusRequest? = null
    private var watchdog: Runnable? = null

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        mainHandler.post {
            if (status == TextToSpeech.SUCCESS) {
                Log.i(TAG, "TTS engine ready")
                wireListener()
                applySpeechAudioAttributes()
                ensureLanguage(Locale.US)
                ready.value = true
                flushPending()
            } else {
                Log.e(TAG, "TTS engine init failed status=$status")
                ready.value = false
            }
        }
    }

    val isReady: Boolean get() = ready.value

    suspend fun awaitReady(timeoutMs: Long = 10_000L): Boolean {
        if (ready.value) return true
        return withTimeoutOrNull(timeoutMs) {
            ready.first { it }
            true
        } ?: false
    }

    fun setLanguage(languageCode: String) {
        val locale = when (languageCode) {
            "pt" -> Locale("pt", "BR")
            else -> Locale.US
        }
        ensureLanguage(locale)
    }

    fun setPitch(pitch: Float) {
        tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun speak(text: String, completion: () -> Unit = {}) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            mainHandler.post(completion)
            return
        }
        if (!ready.value) {
            Log.i(TAG, "TTS not ready — queueing (${clean.length} chars)")
            pendingSpeak.set(clean to completion)
            return
        }
        doSpeak(clean, completion)
    }

    fun stop() {
        cancelWatchdog()
        pendingSpeak.set(null)
        onDone.set(null)
        runCatching { tts.stop() }
        abandonAudioFocus()
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }

    private fun flushPending() {
        val pending = pendingSpeak.getAndSet(null) ?: return
        doSpeak(pending.first, pending.second)
    }

    private fun doSpeak(text: String, completion: () -> Unit) {
        mainHandler.post {
            requestAudioFocus()
            ensureAudibleMusicVolume()
            val id = UUID.randomUUID().toString()
            onDone.set(completion)
            val params = Bundle().apply {
                // KEY_PARAM_STREAM expects the stream id as a String. STREAM_TTS aliases to MUSIC.
                putString(
                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                    AudioManager.STREAM_MUSIC.toString()
                )
                putString(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0")
            }
            Log.i(TAG, "speak len=${text.length} id=$id preview='${text.take(48)}'")
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "tts.speak returned ERROR")
                finishUtterance()
                return@post
            }
            // Some engines drop utterance callbacks — don't stall the session forever.
            val timeoutMs = (2_500L + text.length * 80L).coerceIn(4_000L, 45_000L)
            val token = Runnable {
                Log.w(TAG, "TTS watchdog fired — completing without onDone id=$id")
                finishUtterance()
            }
            watchdog = token
            mainHandler.postDelayed(token, timeoutMs)
        }
    }

    private fun finishUtterance() {
        cancelWatchdog()
        abandonAudioFocus()
        val cb = onDone.getAndSet(null)
        if (cb != null) mainHandler.post { cb.invoke() }
    }

    private fun cancelWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    private fun wireListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "onStart id=$utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "onDone id=$utteranceId")
                finishUtterance()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "onError id=$utteranceId")
                finishUtterance()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "onError id=$utteranceId code=$errorCode")
                finishUtterance()
            }
        })
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
            Log.w(TAG, "locale $preferred unsupported ($result) — falling back to default")
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
    }

    companion object {
        private const val TAG = "AnaTts"
    }
}
