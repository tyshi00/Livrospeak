package com.tyshi00.livrospeak.library

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

private const val TAG = "ListeningStats"

/**
 * One completed stretch of actually-playing listening time, independent of book source.
 * This is the raw material for the Listening Stats screen -- everything there (time of
 * day, weekly/monthly/yearly totals, streaks, per-book/genre minutes) is derived by
 * summarizing these records, never by re-deriving them from [AudiobookProgress] (which
 * only ever remembers a single rolling position/timestamp per book, not history).
 */
data class ListeningSession(
    val startAtMilliseconds: Long,
    val endAtMilliseconds: Long,
    val source: AudiobookSource,
    val bookId: String,
    val title: String,
    val author: String,
) {
    val durationMilliseconds: Long
        get() = (endAtMilliseconds - startAtMilliseconds).coerceAtLeast(0)
}

private const val LOG_FILE_NAME = "listening_sessions.log"

// Below this, treat it as UI/state noise (a stray tick, a play/pause double-fire) rather
// than a real listening session -- otherwise every brief accidental tap of play would
// pollute the log with a flood of near-zero-length entries.
private const val MINIMUM_SESSION_MILLISECONDS = 5_000L

/**
 * Durable, append-only log of listening sessions, gated entirely behind
 * [LivrospeakSettings.listeningStatsEnabled].
 *
 * Deliberately NOT written from every play()/pause() call site in the app (there are
 * many, spread across LibbyBridge/LocalPlaybackController/PlayerDebugScreen, and some
 * are purely defensive/precautionary rather than real user actions). Instead this is
 * driven by a single [onPlaybackTick] call fed from the same PlayerState.isPlaying /
 * activeBook values the rest of the UI already treats as authoritative (see the main
 * polling LaunchedEffect in PlayerDebugScreen.kt) -- so the session log can never drift
 * out of sync with what the player screen itself is showing.
 *
 * Stored as one JSON object per line (not a single JSON array) so appending a finished
 * session is a cheap file append rather than a full read-modify-rewrite of a growing
 * blob -- this can accumulate for years of listening.
 *
 * Known limitation: an in-progress session lives only in memory. If the process is
 * killed mid-session (not a normal pause, an actual process death) that stretch of
 * listening time is lost rather than partially recorded, since there's no reliable way
 * to know how long it actually continued after the last tick. This is considered
 * acceptable for now -- worth revisiting only if that turns out to happen often enough
 * in practice to noticeably undercount real listening time.
 */
object ListeningSessionStore {
    private lateinit var appContext: Context
    private var openSession: OpenSession? = null

    private data class OpenSession(
        val source: AudiobookSource,
        val bookId: String,
        val title: String,
        val author: String,
        val startAtMilliseconds: Long,
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Call on every playback-state update (the ~700ms Libby bridge poll, and any time
     * LocalPlaybackController's state changes) with whichever book is CURRENTLY the
     * active one and whether it is genuinely playing right now -- not merely "was the
     * play button tapped," but the polled/observed state, so a stuck or unresponsive
     * player (see the still-open Libby playback bug) never gets counted as listening
     * time it didn't actually deliver.
     *
     * [book] should be null when nothing is active. Passing a different book than the
     * one currently open closes the previous session first, using [atMillis] as its end
     * time -- this is why callers should invoke this on a steady short-interval tick
     * rather than only on discrete play/pause events: the gap between "actually stopped"
     * and "next tick observes it" bounds how much a session's recorded end time can
     * overshoot reality.
     */
    fun onPlaybackTick(book: Audiobook?, isPlaying: Boolean, atMillis: Long = System.currentTimeMillis()) {
        if (!LivrospeakSettingsStore.current.listeningStatsEnabled) {
            // Stats got turned off (possibly mid-session) -- drop whatever was open
            // rather than record a session partly collected while the feature was off.
            if (openSession != null) {
                Log.d(TAG, "Stats disabled -- dropping open session for ${openSession?.bookId}")
            }
            openSession = null
            return
        }
        val current = openSession
        val sameBook = current != null && book != null &&
            current.source == book.source && current.bookId == book.id

        if (isPlaying && book != null) {
            if (!sameBook) {
                closeOpenSession(atMillis)
                Log.d(TAG, "Session opened: ${book.source}/${book.id} (\"${book.title}\") at $atMillis")
                openSession = OpenSession(book.source, book.id, book.title, book.author, atMillis)
            }
        } else if (current != null) {
            closeOpenSession(atMillis)
        }
    }

    private fun closeOpenSession(endAtMillis: Long) {
        val session = openSession ?: return
        openSession = null
        val durationMillis = endAtMillis - session.startAtMilliseconds
        if (durationMillis < MINIMUM_SESSION_MILLISECONDS) {
            Log.d(
                TAG,
                "Session discarded (too short): ${session.source}/${session.bookId} " +
                    "lasted ${durationMillis}ms, needs ${MINIMUM_SESSION_MILLISECONDS}ms",
            )
            return
        }
        Log.d(
            TAG,
            "Session closed: ${session.source}/${session.bookId} (\"${session.title}\") " +
                "lasted ${durationMillis}ms -- writing to log",
        )
        appendToLog(
            ListeningSession(
                startAtMilliseconds = session.startAtMilliseconds,
                endAtMilliseconds = endAtMillis,
                source = session.source,
                bookId = session.bookId,
                title = session.title,
                author = session.author,
            ),
        )
    }

    private fun appendToLog(session: ListeningSession) {
        val json = JSONObject()
            .put("start", session.startAtMilliseconds)
            .put("end", session.endAtMilliseconds)
            .put("source", session.source.name)
            .put("id", session.bookId)
            .put("title", session.title)
            .put("author", session.author)
        val result = runCatching {
            File(appContext.filesDir, LOG_FILE_NAME).appendText(json.toString() + "\n")
        }
        result.onFailure {
            Log.e(TAG, "Failed to append listening session to $LOG_FILE_NAME", it)
        }.onSuccess {
            Log.d(TAG, "Appended listening session for ${session.bookId} to $LOG_FILE_NAME")
        }
    }

    /** Everything logged so far, oldest first. Used by the Listening Stats screen. */
    fun readAllSessions(): List<ListeningSession> {
        val file = File(appContext.filesDir, LOG_FILE_NAME)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching {
                val json = JSONObject(line)
                ListeningSession(
                    startAtMilliseconds = json.getLong("start"),
                    endAtMilliseconds = json.getLong("end"),
                    source = AudiobookSource.valueOf(json.getString("source")),
                    bookId = json.getString("id"),
                    title = json.optString("title"),
                    author = json.optString("author"),
                )
            }.getOrNull()
        }
    }
}
