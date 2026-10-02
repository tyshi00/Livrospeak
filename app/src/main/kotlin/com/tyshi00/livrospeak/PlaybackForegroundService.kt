package com.tyshi00.livrospeak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
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

        private val running = AtomicBoolean(false)
        private val libbyPlaying = AtomicBoolean(false)
        private val localPlaying = AtomicBoolean(false)
        private val mutableStartFailed = MutableStateFlow(false)
        val startFailed: StateFlow<Boolean> = mutableStartFailed.asStateFlow()

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
            } else if (running.compareAndSet(true, false)) {
                mutableStartFailed.value = false
                appContext.stopService(
                    Intent(appContext, PlaybackForegroundService::class.java),
                )
            }
        }
    }
}
