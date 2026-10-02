package com.tyshi00.livrospeak.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tyshi00.livrospeak.PlaybackForegroundService
import com.tyshi00.livrospeak.library.Audiobook
import com.tyshi00.livrospeak.library.AudiobookProgressStore
import com.tyshi00.livrospeak.library.AudiobookSource
import com.tyshi00.livrospeak.library.RssDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "LocalPlayback"
private const val PROGRESS_SAVE_INTERVAL_MILLISECONDS = 7_000L

/**
 * How long to wait for MediaPlayer's async prepare to call back (either
 * setOnPreparedListener or setOnErrorListener) before giving up on it. Normally one
 * of those two always fires -- but a real device test with a large (~460MB) m4b
 * turned up a third case neither this code nor MediaPlayer's own docs account for:
 * prepareAsync() can simply hang forever with NEITHER callback ever firing (most
 * likely the native parser stalling while scanning for a "moov" atom placed at the
 * end of the file, common for audiobook-authoring tools), leaving the player frozen
 * on "Preparing…" indefinitely with no error, no crash, and nothing to log. Without
 * this watchdog that looked -- and behaved -- identically to the app just being
 * broken, with no way to tell the two apart from the outside.
 */
private const val PREPARE_TIMEOUT_MILLISECONDS = 20_000L

object LocalPlaybackController {
    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PlayerState(diagnostic = "No local book loaded"))
    private var player: MediaPlayer? = null
    private var activeBook: Audiobook? = null
    private var speed = 1f
    private var lastSavedAt = 0L

    val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun open(book: Audiobook, autoPlay: Boolean = false) {
        persistProgress()
        releasePlayer()
        activeBook = book
        val saved = AudiobookProgressStore.read(book.source, book.id)
        speed = saved.playbackSpeed.coerceIn(1f, 2f)
        mutableState.value = PlayerState(
            title = book.title,
            chapter = book.author.takeIf { it.isNotBlank() },
            positionSeconds = saved.positionMilliseconds / 1000.0,
            durationSeconds = (book.durationMilliseconds.takeIf { it > 0 }
                ?: saved.durationMilliseconds) / 1000.0,
            playbackSpeed = speed.toDouble(),
            diagnostic = "Preparing…",
            readiness = PlayerReadiness.Preparing,
        )

        try {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                val playbackReference = if (book.source == AudiobookSource.Rss) {
                    RssDownloadManager.verifiedLocalReference(book) ?: book.playbackReference
                } else {
                    book.playbackReference
                }
                val playbackUri = Uri.parse(playbackReference)
                if (playbackUri.scheme.equals("http", true) || playbackUri.scheme.equals("https", true)) {
                    setDataSource(playbackReference)
                } else if (playbackUri.scheme.isNullOrBlank()) {
                    setDataSource(playbackReference)
                } else {
                    setDataSource(appContext, playbackUri)
                }
                setOnPreparedListener { prepared ->
                    if (!autoPlay && prepared.isPlaying) prepared.pause()
                    val target = saved.positionMilliseconds.coerceIn(0, prepared.duration.toLong().coerceAtLeast(0))
                    if (target > 0) prepared.seekTo(target.toInt())
                    applySpeed(prepared)
                    if (!autoPlay && prepared.isPlaying) prepared.pause()
                    updateState(prepared)
                    if (autoPlay) prepared.start()
                    updateState(prepared)
                    Log.d(TAG, "playback prepared")
                    scheduleUpdates()
                }
                setOnInfoListener { mediaPlayer, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            mutableState.value = mutableState.value.copy(
                                readiness = PlayerReadiness.Buffering,
                                diagnostic = "Buffering…",
                            )
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> updateState(mediaPlayer)
                    }
                    false
                }
                setOnCompletionListener { completed ->
                    updateState(completed)
                    persistProgress()
                }
                setOnErrorListener { _, what, _ ->
                    Log.w(TAG, "playback failed reason=$what")
                    PlaybackForegroundService.updateLocal(appContext, false)
                    mutableState.value = mutableState.value.copy(
                        isPlaying = false,
                        diagnostic = if (book.source == AudiobookSource.Rss) {
                            "Not available offline"
                        } else {
                            "This audiobook could not be played."
                        },
                        readiness = PlayerReadiness.Error,
                    )
                    true
                }
                prepareAsync()
            }
            // See PREPARE_TIMEOUT_MILLISECONDS -- guards against prepareAsync() never
            // calling back at all. The reference-identity check means this is a
            // no-op if the user has since opened a different book (player now points
            // at a different MediaPlayer); the readiness check means it's a no-op if
            // prepare already genuinely succeeded or failed on its own. Buffering is
            // treated as "still stuck" too, not just Preparing -- a large local file
            // can report MEDIA_INFO_BUFFERING_START while the native decoder churns
            // through it, which would otherwise move readiness out of Preparing and
            // make this check think everything's fine, even if BUFFERING_END (or
            // anything else) then never follows.
            val stillStuckReadiness = setOf(PlayerReadiness.Preparing, PlayerReadiness.Buffering)
            handler.postDelayed({
                if (player === mediaPlayer && mutableState.value.readiness in stillStuckReadiness) {
                    Log.w(TAG, "playback failed reason=prepare-timeout")
                    releasePlayer()
                    PlaybackForegroundService.updateLocal(appContext, false)
                    mutableState.value = mutableState.value.copy(
                        isPlaying = false,
                        diagnostic = if (book.source == AudiobookSource.Rss) {
                            "Not available offline"
                        } else {
                            "This audiobook could not be played."
                        },
                        readiness = PlayerReadiness.Error,
                    )
                }
            }, PREPARE_TIMEOUT_MILLISECONDS)
        } catch (_: Exception) {
            Log.w(TAG, "playback failed reason=unreadable")
            PlaybackForegroundService.updateLocal(appContext, false)
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                diagnostic = if (book.source == AudiobookSource.Rss) {
                    "Not available offline"
                } else {
                    "This audiobook could not be played."
                },
                readiness = PlayerReadiness.Error,
            )
        }
    }

    fun play() {
        if (mutableState.value.readiness != PlayerReadiness.Ready) return
        player?.runCatching {
            start()
            updateState(this)
            scheduleUpdates()
        }
    }

    fun pause() {
        player?.runCatching {
            if (isPlaying) pause()
            updateState(this)
            persistProgress()
        }
    }

    fun seekBy(deltaMilliseconds: Long) {
        val current = player ?: return
        seekTo(current.currentPosition.toLong() + deltaMilliseconds)
    }

    fun seekTo(positionMilliseconds: Long) {
        player?.runCatching {
            val target = positionMilliseconds.coerceIn(0, duration.toLong().coerceAtLeast(0))
            seekTo(target.toInt())
            mutableState.value = mutableState.value.copy(positionSeconds = target / 1000.0)
            persistProgress()
        }
    }

    fun setSpeed(value: Double) {
        speed = value.toFloat().coerceIn(1f, 2f)
        player?.let(::applySpeed)
        mutableState.value = mutableState.value.copy(playbackSpeed = speed.toDouble())
        persistProgress()
    }

    fun persistProgress() {
        val book = activeBook ?: return
        val current = player
        val position = runCatching { current?.currentPosition?.toLong() }.getOrNull()
            ?: (mutableState.value.positionSeconds * 1000).toLong()
        val duration = runCatching { current?.duration?.toLong() }.getOrNull()
            ?: (mutableState.value.durationSeconds * 1000).toLong()
        AudiobookProgressStore.saveLocal(book, position, duration, speed)
        lastSavedAt = System.currentTimeMillis()
    }

    fun close() {
        persistProgress()
        releasePlayer()
        PlaybackForegroundService.updateLocal(appContext, false)
        activeBook = null
        mutableState.value = PlayerState(
            diagnostic = "No audiobook loaded",
            readiness = PlayerReadiness.Unavailable,
        )
    }

    private fun applySpeed(mediaPlayer: MediaPlayer) {
        runCatching {
            mediaPlayer.playbackParams = PlaybackParams().setSpeed(speed)
        }
    }

    private fun updateState(mediaPlayer: MediaPlayer) {
        val duration = runCatching { mediaPlayer.duration }.getOrDefault(0).coerceAtLeast(0)
        val position = runCatching { mediaPlayer.currentPosition }.getOrDefault(0).coerceAtLeast(0)
        val playing = runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
        PlaybackForegroundService.updateLocal(appContext, playing)
        mutableState.value = mutableState.value.copy(
            positionSeconds = position / 1000.0,
            durationSeconds = duration / 1000.0,
            isPlaying = playing,
            playbackSpeed = speed.toDouble(),
            controlsFound = true,
            diagnostic = "Local audiobook ready",
            readiness = PlayerReadiness.Ready,
        )
    }

    private fun scheduleUpdates() {
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            val current = player ?: return
            updateState(current)
            if (current.isPlaying && System.currentTimeMillis() - lastSavedAt >= PROGRESS_SAVE_INTERVAL_MILLISECONDS) {
                persistProgress()
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(updateRunnable)
        player?.runCatching { release() }
        player = null
    }
}
