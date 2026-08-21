package com.anaalarm.alarm

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anaalarm.AnaAlarmApp
import com.anaalarm.R
import com.anaalarm.telemetry.LatencyMetric
import com.anaalarm.telemetry.LatencyMetrics
import com.anaalarm.ui.wakeup.WakeUpActivity
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Posts the full-screen alarm notification and brings [WakeUpActivity] over other apps.
 * Uses PendingIntent.send() with sender-side BAL options — startActivity() from a
 * background FGS is blocked on Android 14+.
 */
class AlarmService : Service() {

    private val handler by lazy { Handler(mainLooper) }
    private var activeAlarmId = -1L
    private var activeNotificationId = -1
    private var fallbackActive = false
    private val ringtonePreparation = PlaybackPreparationGate<MediaPlayer>(::releasePlayer)
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var playbackWakeLock: PowerManager.WakeLock? = null
    private var deliveryStartedAtNanos = 0L
    private var firstAudioMetricRecorded = false
    private val ringtoneExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AnaAlarm-ringtone").apply { isDaemon = true }
    }
    private val repeatTone = object : Runnable {
        override fun run() {
            if (!fallbackActive) return
            val accepted = runCatching {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 1_000) == true
            }.getOrDefault(false)
            if (accepted) recordFirstAudio("generated_tone")
            handler.postDelayed(this, 1_500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L) ?: -1L

        if (intent?.action == ACTION_STOP) {
            pendingStopForAlarmId = NO_PENDING_STOP
            stopAlarm()
            return START_NOT_STICKY
        }

        // A stop queued while this start was still in flight applies only to its own alarm;
        // a different alarm's delivery must survive it.
        val pendingStop = pendingStopForAlarmId
        if (pendingStop != NO_PENDING_STOP && (pendingStop == -1L || pendingStop == alarmId)) {
            pendingStopForAlarmId = NO_PENDING_STOP
            stopAlarm()
            return START_NOT_STICKY
        }

        if (alarmId < 0L) {
            Log.w(TAG, "Ignoring AlarmService start without a valid alarm id")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val notificationId =
            Notifications.ALARM_NOTIFICATION_ID_BASE + alarmId.toInt().coerceAtLeast(0)

        val fullScreenPending = activityPendingIntent(alarmId.toInt(), alarmId)
        val contentPending = activityPendingIntent(alarmId.toInt() + 10_000, alarmId)

        val previousNotificationId = activeNotificationId
        startAsForeground(notificationId, buildSessionNotification(this, contentPending))
        if (previousNotificationId >= 0 && previousNotificationId != notificationId) {
            NotificationManagerCompat.from(this).cancel(previousNotificationId)
        }

        val audioAlreadyActive = fallbackActive
        val alreadyRunning = audioAlreadyActive && activeAlarmId == alarmId
        activeAlarmId = alarmId
        activeNotificationId = notificationId
        if (!alreadyRunning) {
            val deliveredAtNanos =
                intent?.getLongExtra(EXTRA_DELIVERY_STARTED_NANOS, 0L) ?: 0L
            deliveryStartedAtNanos = deliveredAtNanos
                .takeIf { it > 0L }
                ?: LatencyMetrics.nowNanos()
            firstAudioMetricRecorded = false
            if (audioAlreadyActive) recordFirstAudio("already_active")
        }

        // Do not put ringtone provider I/O in front of the user-visible alarm. Android 15+
        // can also report PendingIntent.send() success even when a background launch is blocked,
        // so the full-screen notification is always published as an independent wake-up path.
        turnScreenOn()
        val canPostNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canPostNotifications) {
            runCatching {
                NotificationManagerCompat.from(this).notify(
                    notificationId,
                    buildAlarmNotification(this, fullScreenPending, contentPending)
                )
            }.onFailure { Log.w(TAG, "Unable to publish full-screen alarm notification", it) }
        } else {
            Log.w(TAG, "Notification permission denied; relying on direct wake UI and local alarm")
        }

        // A redelivered or duplicate PendingIntent must not open another activity or start a
        // second media/vibration loop for the same active alarm.
        if (alreadyRunning) return START_REDELIVER_INTENT

        runCatching {
            sendWakeUp(fullScreenPending)
            Log.i(TAG, "WakeUp PendingIntent.send() ok for alarmId=$alarmId")
        }.onFailure { err ->
            Log.w(TAG, "PendingIntent.send failed, trying startActivity", err)
            runCatching {
                startActivity(wakeUpIntent(this, alarmId))
            }.onFailure { e2 ->
                Log.e(TAG, "startActivity also failed — user must tap notification", e2)
            }
        }

        startFallbackAlarm(alarmId)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        stopFallbackAlarm()
        ringtoneExecutor.shutdownNow()
        removeForegroundNotification()
        super.onDestroy()
    }

    private fun startFallbackAlarm(alarmId: Long) {
        if (fallbackActive) return
        fallbackActive = true
        fallbackActiveForTest = true
        val preparationToken = ringtonePreparation.begin(alarmId)
        acquirePlaybackWakeLock()
        startVibration()
        // Give the user immediate sound while the configured ringtone is prepared off the
        // service main path. The generated tone remains active if the provider is slow or fails.
        startGeneratedTone()

        val alarmUri = resolveAlarmSound(alarmId)
        if (alarmUri == null) return

        ringtoneExecutor.execute {
            val player = MediaPlayer()
            runCatching {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                // ContentProvider access can block; keep it entirely off the service/main thread.
                player.setDataSource(applicationContext, alarmUri)
                player.isLooping = true
                player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            }.onSuccess {
                handler.post { attachPreparedPlayer(preparationToken, player) }
            }.onFailure { error ->
                Log.e(TAG, "Configured alarm sound failed; keeping generated tone", error)
                runCatching { player.release() }
            }
        }
    }

    /**
     * Per-alarm custom sound when one is configured, else the system default chain. The
     * credential-protected row is consulted only after unlock; before that the device-protected
     * mirror carries the reference. Resolution runs on the ringtone executor, never the main
     * path — the generated tone is already audible while this happens.
     */
    private fun resolveAlarmSound(alarmId: Long): Uri? {
        val app = applicationContext as? AnaAlarmApp
        val custom: String? = if (app != null && app.isUserUnlocked() && app.ensureCredentialStorage()) {
            runCatching {
                runBlocking { app.memoryStore.getAlarm(alarmId)?.ringtoneUri }
            }.getOrNull()
        } else {
            null
        } ?: app?.let { application ->
            runCatching {
                application.alarmScheduler.directBootSnapshot(alarmId)?.ringtoneUri
            }.getOrNull()
        }
        if (custom.isNullOrBlank()) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
        return runCatching { Uri.parse(custom) }.getOrNull()
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    }

    private fun attachPreparedPlayer(
        preparationToken: PlaybackPreparationGate.Token,
        player: MediaPlayer
    ) {
        if (!fallbackActive) {
            releasePlayer(player)
            return
        }
        if (!ringtonePreparation.attach(preparationToken, player)) return

        player.setOnPreparedListener { prepared ->
            if (!fallbackActive || !ringtonePreparation.owns(preparationToken, prepared)) {
                if (!ringtonePreparation.releaseIfOwned(preparationToken, prepared)) {
                    releasePlayer(prepared)
                }
                return@setOnPreparedListener
            }
            runCatching { prepared.start() }
                .onSuccess {
                    recordFirstAudio("ringtone")
                    stopGeneratedTone()
                }
                .onFailure { error ->
                    Log.e(TAG, "Prepared alarm sound failed; keeping generated tone", error)
                    ringtonePreparation.releaseIfOwned(preparationToken, prepared)
                }
        }
        player.setOnErrorListener { failedPlayer, what, extra ->
            Log.e(TAG, "Alarm MediaPlayer error what=$what extra=$extra")
            handler.post {
                if (ringtonePreparation.releaseIfOwned(preparationToken, failedPlayer)) {
                    if (fallbackActive && toneGenerator == null) startGeneratedTone()
                }
            }
            true
        }
        runCatching { player.prepareAsync() }
            .onFailure { error ->
                Log.e(TAG, "Async alarm preparation failed; keeping generated tone", error)
                ringtonePreparation.releaseIfOwned(preparationToken, player)
            }
    }

    private fun releasePlayer(player: MediaPlayer) {
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.release() }
    }

    private fun startGeneratedTone() {
        if (toneGenerator != null) return
        runCatching {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            handler.post(repeatTone)
        }.onFailure { error ->
            Log.e(TAG, "Generated alarm tone also failed", error)
            toneGenerator = null
        }
    }

    private fun stopGeneratedTone() {
        handler.removeCallbacks(repeatTone)
        toneGenerator?.let { tone ->
            runCatching { tone.stopTone() }
            runCatching { tone.release() }
        }
        toneGenerator = null
    }

    private fun recordFirstAudio(path: String) {
        if (firstAudioMetricRecorded || deliveryStartedAtNanos <= 0L) return
        firstAudioMetricRecorded = true
        LatencyMetrics.record(
            metric = LatencyMetric.ALARM_TO_FIRST_AUDIO,
            startedAtNanos = deliveryStartedAtNanos,
            outcome = "started",
            "alarm_id" to activeAlarmId,
            "audio_path" to path
        )
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.takeIf { it.hasVibrator() }?.let { alarmVibrator ->
            runCatching {
                alarmVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
                )
            }.onFailure { error ->
                Log.w(TAG, "Alarm vibration failed", error)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquirePlaybackWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        playbackWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "anaalarm:alarm-playback"
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire() }
                .onFailure { Log.w(TAG, "Alarm playback wake lock failed", it) }
        }
    }

    private fun stopFallbackAlarm() {
        fallbackActiveForTest = false
        if (!fallbackActive && !ringtonePreparation.hasAttachment() && toneGenerator == null &&
            vibrator == null &&
            playbackWakeLock == null
        ) {
            return
        }
        fallbackActive = false
        handler.removeCallbacks(repeatTone)
        ringtonePreparation.invalidate()
        stopGeneratedTone()
        runCatching { vibrator?.cancel() }
        vibrator = null
        playbackWakeLock?.let { wakeLock ->
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
        }
        playbackWakeLock = null
    }

    private fun stopAlarm() {
        Log.i(TAG, "Stopping active fallback alarmId=$activeAlarmId")
        stopFallbackAlarm()
        removeForegroundNotification()
        stopSelf()
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        if (activeNotificationId >= 0) {
            NotificationManagerCompat.from(this).cancel(activeNotificationId)
        }
        activeAlarmId = -1L
        activeNotificationId = -1
    }

    private fun sendWakeUp(pending: PendingIntent) {
        val options: Bundle? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        backgroundActivityStartMode()
                    )
                }.toBundle()
            } else null
        pending.send(this, 0, null, null, null, null, options)
    }

    private fun startAsForeground(notificationId: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground(systemExempted) failed, falling back", e)
        }
        @Suppress("DEPRECATION")
        startForeground(notificationId, notification)
    }

    private fun activityPendingIntent(requestCode: Int, alarmId: Long): PendingIntent {
        val wakeIntent = wakeUpIntent(this, alarmId)
        val creatorOptions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        backgroundActivityStartMode()
                    )
                }.toBundle()
            } else null
        return PendingIntent.getActivity(
            this,
            requestCode,
            wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions
        )
    }

    private fun turnScreenOn() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "anaalarm:alarm"
        )
        runCatching {
            wakeLock.acquire(5_000)
            wakeLock.release()
        }
    }

    companion object {
        private const val TAG = "AnaAlarm"
        private const val ACTION_START = "com.anaalarm.action.START_ALARM_SERVICE"
        private const val ACTION_STOP = "com.anaalarm.action.STOP_ALARM_SERVICE"
        private const val EXTRA_DELIVERY_STARTED_NANOS = "extra_delivery_started_nanos"
        private const val STOP_REQUEST_CODE = 69_999

        /** Sentinel meaning "no stop request is queued". */
        private const val NO_PENDING_STOP = Long.MIN_VALUE

        /**
         * Alarm id whose delivery should be suppressed if its start command is still queued.
         * [-1] matches any alarm (legacy behavior for sessions without a concrete id).
         */
        @Volatile
        private var pendingStopForAlarmId: Long = NO_PENDING_STOP

        @Volatile
        private var fallbackActiveForTest = false

        /** Process-local signal used by the end-to-end alarm contract test. */
        internal fun isFallbackActiveForTest(): Boolean = fallbackActiveForTest

        @SuppressLint("InlinedApi")
        @Suppress("DEPRECATION")
        private fun backgroundActivityStartMode(): Int =
            if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }

        fun start(
            context: Context,
            alarmId: Long,
            deliveredAtNanos: Long = LatencyMetrics.nowNanos()
        ) {
            // A fresh delivery for this alarm supersedes a stale queued stop for the same id,
            // while stops queued for other alarms remain pending.
            if (pendingStopForAlarmId == alarmId) {
                pendingStopForAlarmId = NO_PENDING_STOP
            }
            val intent = Intent(context, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_DELIVERY_STARTED_NANOS, deliveredAtNanos)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops sound, vibration, foreground state and the ongoing alarm notification.
         * [alarmId] scopes the request to one alarm so an overlapping delivery for another
         * alarm is not silenced; [-1] matches any alarm. Safe to call repeatedly, including
         * just before the service has finished starting.
         */
        @SuppressLint("ImplicitSamInstance")
        fun stop(context: Context, alarmId: Long = -1L) {
            pendingStopForAlarmId = alarmId
            context.stopService(Intent(context, AlarmService::class.java))
        }

        fun wakeUpIntent(context: Context, alarmId: Long): Intent =
            Intent(context, WakeUpActivity::class.java)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )

        fun buildSessionNotification(
            context: Context,
            contentPending: PendingIntent
        ): Notification =
            NotificationCompat.Builder(context, Notifications.SILENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.wake_up_title))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(contentPending)
                .setSilent(true)
                .setOngoing(true)
                .addAction(
                    R.drawable.ic_stat_alarm,
                    context.getString(R.string.stop),
                    stopPendingIntent(context)
                )
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

        fun buildAlarmNotification(
            context: Context,
            fullScreenPending: PendingIntent,
            contentPending: PendingIntent
        ): Notification =
            NotificationCompat.Builder(context, Notifications.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.wake_up_title))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPending, true)
                .setContentIntent(contentPending)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(
                    R.drawable.ic_stat_alarm,
                    context.getString(R.string.stop),
                    stopPendingIntent(context)
                )
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

        private fun stopPendingIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context,
                STOP_REQUEST_CODE,
                Intent(context, AlarmService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
