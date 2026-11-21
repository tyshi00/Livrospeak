package com.tyshi00.livrospeak

import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.tyshi00.livrospeak.library.Audiobook
import com.tyshi00.livrospeak.library.AudiobookProgressStore
import com.tyshi00.livrospeak.library.AudiobookSource
import com.tyshi00.livrospeak.player.LocalPlaybackController
import com.tyshi00.livrospeak.player.PlayerState
import kotlin.math.abs

/**
 * Publishes what Livrospeak is playing as a platform media session, so LightOS can
 * show its own playback controls for it (the lock screen, per the Light SDK).
 *
 * This follows the contract the Light SDK's own detached audio player uses
 * (light-sdk `docs/design_decisions/detached_audio.md`, and `LightAudioService` /
 * `AudioTypes.kt` in `sdk/client`):
 *  - LightOS finds active sessions through Android's MediaSessionManager and draws
 *    its own controls from the session's metadata, playback state and actions.
 *  - The session extra `com.thelightphone.audio.USAGE` picks LightOS's layout;
 *    "speech" gives play/pause with 15-second skips.
 *  - Speech advertises rewind and fast-forward and withholds previous/next. LightOS's
 *    rewind and fast-forward arrive here as [MediaSession.Callback.onRewind] and
 *    [MediaSession.Callback.onFastForward].
 *  - The session activity opens the app when the controls are tapped.
 * The SDK's player cannot be used directly: it plays audio files it owns, and Libby's
 * audio comes from the hidden Libby web player. So the session is published here,
 * and its buttons drive whichever source is playing, through the same calls the
 * player screen's own buttons use.
 *
 * The session is shown while something is playing and for
 * [PlaybackForegroundService.STOP_GRACE_MILLISECONDS] after it last played: the same
 * window in which the background playback service keeps the app running, so the
 * controls are only offered while the app can still act on them.
 *
 * Everything here runs on the main thread: [update] is called from the player
 * screen's polling loop, and the session's callbacks are delivered on the main
 * looper.
 */
object NowPlayingSession {
    private const val TAG = "NowPlayingSession"

    /** LightOS contract, from the Light SDK's AudioTypes.kt. Do not rename. */
    private const val SESSION_USAGE_EXTRA = "com.thelightphone.audio.USAGE"
    private const val SESSION_USAGE_SPEECH = "speech"

    /** The same step the player screen's own skip buttons use. */
    private const val SKIP_MILLISECONDS = 15_000L

    /** A published position further than this from where it should be is corrected. */
    private const val POSITION_DRIFT_MILLISECONDS = 1_500L

    private var appContext: Context? = null
    private var session: MediaSession? = null

    // What the buttons act on: the book and the state last passed to update().
    private var currentBook: Audiobook? = null
    private var currentState: PlayerState? = null

    /** elapsedRealtime of the last update that reported playing; 0 if none yet. */
    private var lastPlayingAt = 0L

    private var publishedMetadata: MetadataKey? = null
    private var publishedPlayback: PlaybackKey? = null

    private data class MetadataKey(val title: String, val artist: String, val durationMs: Long)

    private data class PlaybackKey(
        val isPlaying: Boolean,
        val positionMs: Long,
        val speed: Float,
        val updatedAt: Long,
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Called on every tick of the player screen's polling loop with the book the app
     * is showing as active (null for none), that source's player state, and whether
     * it is actually playing.
     */
    fun update(book: Audiobook?, state: PlayerState, isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (book != null && isPlaying) lastPlayingAt = now
        currentBook = book
        currentState = state

        val recentlyPlayed = lastPlayingAt != 0L &&
            now - lastPlayingAt < PlaybackForegroundService.STOP_GRACE_MILLISECONDS
        if (book == null || !(isPlaying || recentlyPlayed)) {
            deactivate()
            return
        }

        val active = session ?: createSession() ?: return
        publishMetadata(active, book, state)
        publishPlayback(active, state, isPlaying, now)
        if (!active.isActive) {
            active.isActive = true
            Log.d(TAG, "session active")
        }
    }

    private fun deactivate() {
        val existing = session ?: return
        if (existing.isActive) {
            existing.isActive = false
            Log.d(TAG, "session inactive")
        }
        publishedMetadata = null
        publishedPlayback = null
    }

    private fun createSession(): MediaSession? {
        val context = appContext ?: return null
        return try {
            MediaSession(context, "Livrospeak").also { created ->
                created.setCallback(callback, Handler(Looper.getMainLooper()))
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                    created.setSessionActivity(
                        PendingIntent.getActivity(
                            context,
                            0,
                            launch,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
                created.setExtras(Bundle().apply { putString(SESSION_USAGE_EXTRA, SESSION_USAGE_SPEECH) })
                created.setPlaybackToLocal(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                session = created
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "could not create media session: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun publishMetadata(target: MediaSession, book: Audiobook, state: PlayerState) {
        val key = MetadataKey(
            title = book.title,
            artist = book.author,
            durationMs = secondsToMs(state.durationSeconds),
        )
        if (key == publishedMetadata) return
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, key.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, key.artist)
        if (key.durationMs > 0) builder.putLong(MediaMetadata.METADATA_KEY_DURATION, key.durationMs)
        target.setMetadata(builder.build())
        publishedMetadata = key
    }

    private fun publishPlayback(target: MediaSession, state: PlayerState, isPlaying: Boolean, now: Long) {
        val positionMs = secondsToMs(state.positionSeconds)
        val speed = if (isPlaying) state.playbackSpeed.toFloat().takeIf { it > 0f } ?: 1f else 0f
        val previous = publishedPlayback
        if (previous != null &&
            previous.isPlaying == isPlaying &&
            previous.speed == speed &&
            abs(expectedPosition(previous, now) - positionMs) <= POSITION_DRIFT_MILLISECONDS
        ) {
            return
        }
        // Mirrors what media3 publishes for the Light SDK's speech usage: play or pause
        // (whichever applies) with play/pause, rewind and fast-forward for the 15 s
        // skips, seek and stop, and no previous/next.
        val actions = PlaybackState.ACTION_PLAY_PAUSE or
            (if (isPlaying) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY) or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        target.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs,
                    speed,
                    now,
                )
                .build(),
        )
        publishedPlayback = PlaybackKey(isPlaying, positionMs, speed, now)
    }

    private fun expectedPosition(published: PlaybackKey, now: Long): Long =
        published.positionMs + ((now - published.updatedAt) * published.speed).toLong()

    private fun secondsToMs(seconds: Double): Long =
        if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else 0L

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = play()
        override fun onPause() = pause()

        // Stop from a system control pauses: the book and its place are kept.
        override fun onStop() = pause()
        override fun onRewind() = skip(-SKIP_MILLISECONDS)
        override fun onFastForward() = skip(SKIP_MILLISECONDS)
        override fun onSeekTo(pos: Long) = seekTo(pos)
    }

    // The same calls the player screen's own buttons make (PlayerDebugScreen's
    // PlayerScreen call site), routed by the active book's source.
    private fun play() {
        when (currentBook?.source ?: return) {
            AudiobookSource.Libby -> {
                LocalPlaybackController.pause()
                LibbyBridge.play()
            }
            AudiobookSource.Local, AudiobookSource.Rss, AudiobookSource.Librivox -> {
                LibbyBridge.pause()
                LocalPlaybackController.play()
            }
        }
    }

    private fun pause() {
        val book = currentBook ?: return
        when (book.source) {
            AudiobookSource.Libby -> {
                LibbyBridge.pause()
                currentState?.let { AudiobookProgressStore.recordSnapshot(book, it, force = true) }
            }
            AudiobookSource.Local, AudiobookSource.Rss, AudiobookSource.Librivox ->
                LocalPlaybackController.pause()
        }
    }

    private fun skip(deltaMs: Long) {
        when (currentBook?.source ?: return) {
            AudiobookSource.Libby -> if (deltaMs < 0) LibbyBridge.back15() else LibbyBridge.forward15()
            AudiobookSource.Local, AudiobookSource.Rss, AudiobookSource.Librivox ->
                LocalPlaybackController.seekBy(deltaMs)
        }
    }

    private fun seekTo(positionMs: Long) {
        when (currentBook?.source ?: return) {
            AudiobookSource.Libby -> LibbyBridge.seekTo(positionMs)
            AudiobookSource.Local, AudiobookSource.Rss, AudiobookSource.Librivox ->
                LocalPlaybackController.seekTo(positionMs)
        }
    }
}
