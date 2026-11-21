package com.tyshi00.livrospeak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps Livrospeak's process (and, for Libby, the application-scoped WebView) perceptible
 * to Android while any audiobook source is actively playing -- Libby, local files, or RSS.
 * Without this, the OS is free to freeze or kill a backgrounded process, which is exactly
 * what happens if the device is locked or the app loses focus for more than a short time.
 * The service does not own playback or issue any player command; it only reflects the
 * isPlaying state each source already tracks for itself.
 *
 * It starts as soon as a source reports playing, but it does not stop the moment a
 * source reports not playing: it stops only after [STOP_GRACE_MILLISECONDS] with no
 * playing report at all. See that constant for why.
 */
class PlaybackForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        running.set(true)
        mutableStartFailed.value = false
        Log.d(TAG, "foreground playback host started")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        running.set(false)
        Log.d(TAG, "foreground playback host stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLISECONDS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps audiobook playback active"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val openLivrospeak = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_audio_message_white)
            .setContentTitle("Livrospeak")
            .setContentText("Playback active")
            .setContentIntent(openLivrospeak)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "livrospeak_playback"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "PlaybackService"
        private const val WAKE_LOCK_TAG = "Livrospeak:PlaybackWakeLock"

        /**
         * Safety net only -- normal playback releases and re-acquires this via
         * onDestroy/onStartCommand well before this fires. Without a cap, a wake lock
         * held across a genuine bug (a source reporting isPlaying=true forever without
         * this service ever being told to stop) would drain the battery silently.
         */
        private const val WAKE_LOCK_TIMEOUT_MILLISECONDS = 12 * 60 * 60 * 1000L

        /**
         * How long the service outlives the last "playing" report.
         *
         * A source can report "not playing" for a moment while audio is, to the
         * listener, continuous: the local player reports it between one LibriVox
         * chapter file finishing and the next one starting, and Libby's player is
         * polled about every 700 ms and can read as not playing while it moves from
         * one audio part to the next. Stopping on that single report is not
         * recoverable with the screen off: Android does not allow an app in the
         * background to start a foreground service again, so the service stayed
         * down, and the process was frozen a few seconds into the next chapter
         * (the freeze itself is documented in INVESTIGATION.md) until the app was
         * reopened.
         *
         * So the stop is delayed, and cancelled if anything reports playing in the
         * meantime. A real pause still ends the service; it just does so this much
         * later, with the wake lock held until then.
         *
         * 15 minutes matches the Light SDK's own detached player (LightAudioService
         * stops 15 minutes after playback pauses), so a paused book can be resumed
         * from LightOS's playback controls for that long. [NowPlayingSession] offers
         * those controls for this same window.
         */
        internal const val STOP_GRACE_MILLISECONDS = 15 * 60 * 1000L

        private val running = AtomicBoolean(false)
        private val libbyPlaying = AtomicBoolean(false)
        private val localPlaying = AtomicBoolean(false)
        private val mutableStartFailed = MutableStateFlow(false)
        val startFailed: StateFlow<Boolean> = mutableStartFailed.asStateFlow()

        // The delayed stop. Reports arrive on the main thread except for one path
        // (a LibriVox download removed from its IO coroutine), so the pending stop
        // is guarded by a lock rather than assumed to be main-thread only.
        private val mainHandler = Handler(Looper.getMainLooper())
        private val stopLock = Any()
        private var pendingStop: Runnable? = null

        /** Called from [LibbyBridge] whenever Libby's own play/pause state changes. */
        fun updateLibby(context: Context, isPlaying: Boolean) {
            libbyPlaying.set(isPlaying)
            refresh(context)
        }

        /** Called from LocalPlaybackController for both local files and RSS episodes. */
        fun updateLocal(context: Context, isPlaying: Boolean) {
            localPlaying.set(isPlaying)
            refresh(context)
        }

        private fun refresh(context: Context) {
            val shouldRun = libbyPlaying.get() || localPlaying.get()
            val appContext = context.applicationContext
            if (shouldRun) {
                cancelPendingStop()
                if (running.compareAndSet(false, true)) {
                    try {
                        appContext.startForegroundService(
                            Intent(appContext, PlaybackForegroundService::class.java),
                        )
                    } catch (error: RuntimeException) {
                        running.set(false)
                        mutableStartFailed.value = true
                        Log.e(TAG, "could not start foreground playback host")
                    }
                }
            } else {
                scheduleStop(appContext)
            }
        }

        /**
         * Arms the delayed stop, once. Libby's "not playing" report repeats on every
         * poll while a book is paused; those repeats must not push the deadline
         * back, so a stop that is already pending is left exactly as it is.
         */
        private fun scheduleStop(appContext: Context) {
            synchronized(stopLock) {
                if (pendingStop != null || !running.get()) return
                val stop = object : Runnable {
                    override fun run() {
                        synchronized(stopLock) {
                            // Cancelled, or replaced by a newer stop, after this one
                            // was already queued to run: not this one's call to make.
                            if (pendingStop !== this) return
                            pendingStop = null
                        }
                        val stillIdle = !libbyPlaying.get() && !localPlaying.get()
                        if (stillIdle && running.compareAndSet(true, false)) {
                            mutableStartFailed.value = false
                            appContext.stopService(
                                Intent(appContext, PlaybackForegroundService::class.java),
                            )
                            Log.d(TAG, "foreground playback host released after idle grace")
                        }
                    }
                }
                pendingStop = stop
                mainHandler.postDelayed(stop, STOP_GRACE_MILLISECONDS)
            }
        }

        private fun cancelPendingStop() {
            synchronized(stopLock) {
                pendingStop?.let { mainHandler.removeCallbacks(it) }
                pendingStop = null
            }
        }
    }
}
