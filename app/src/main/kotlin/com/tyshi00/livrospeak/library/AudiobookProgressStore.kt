package com.tyshi00.livrospeak.library

import android.content.Context
import com.tyshi00.livrospeak.player.PlayerState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFERENCES_NAME = "local_audiobook_progress"
private const val SAVE_INTERVAL_MILLISECONDS = 7_000L

data class AudiobookProgress(
    val positionMilliseconds: Long = 0,
    val durationMilliseconds: Long = 0,
    val playbackSpeed: Float = 1f,
    val completed: Boolean = false,
    val lastPlayedAtMilliseconds: Long = 0,
    val lastUpdatedAtMilliseconds: Long = 0,
    val playbackReference: String = "",
    val title: String = "",
    val author: String = "",
    val progressPercentOverride: Int? = null,
    val dueText: String = "",
)

data class PersistedActiveAudiobook(
    val source: AudiobookSource,
    val id: String,
    val playbackReference: String,
    val title: String,
    val author: String,
    val progress: AudiobookProgress,
) {
    val qualifiedId: String
        get() = AudiobookProgressStore.qualifiedId(source, id)
}

/** Durable, source-independent progress and ordering metadata. */
object AudiobookProgressStore {
    private lateinit var appContext: Context
    private val latest = ConcurrentHashMap<String, Pair<Audiobook, PlayerState>>()
    private val lastWrittenAt = ConcurrentHashMap<String, Long>()
    private val mutableActiveAudiobook = MutableStateFlow<PersistedActiveAudiobook?>(null)

    val activeAudiobook: StateFlow<PersistedActiveAudiobook?> =
        mutableActiveAudiobook.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        mutableActiveAudiobook.value = lastActiveAudiobook()
    }

    fun qualifiedId(source: AudiobookSource, id: String): String = "${source.name}:$id"

    fun read(source: AudiobookSource, id: String): AudiobookProgress {
        val preferences = preferences()
        val key = qualifiedId(source, id)
        val legacyPrefix = if (source == AudiobookSource.Local && !preferences.contains("$key.position")) {
            id
        } else {
            key
        }
        return AudiobookProgress(
            positionMilliseconds = preferences.getLong("$legacyPrefix.position", 0L),
            durationMilliseconds = preferences.getLong("$legacyPrefix.duration", 0L),
            playbackSpeed = preferences.getFloat("$legacyPrefix.speed", 1f),
            completed = preferences.getBoolean("$legacyPrefix.completed", false),
            lastPlayedAtMilliseconds = preferences.getLong("$key.lastPlayed", 0L),
            lastUpdatedAtMilliseconds = preferences.getLong(
                "$key.updated",
                preferences.getLong("$legacyPrefix.updated", 0L),
            ),
            playbackReference = preferences.getString("$key.reference", "").orEmpty(),
            title = preferences.getString("$key.title", "").orEmpty(),
            author = preferences.getString("$key.author", "").orEmpty(),
            progressPercentOverride = preferences.getInt("$key.progressPercent", -1)
                .takeIf { it >= 0 },
            dueText = preferences.getString("$key.dueText", "").orEmpty(),
        )
    }

    fun markOpened(book: Audiobook): AudiobookProgress {
        val now = System.currentTimeMillis()
        val current = read(book.source, book.id)
        val updated = current.copy(
            lastPlayedAtMilliseconds = now,
            lastUpdatedAtMilliseconds = now,
            playbackReference = book.playbackReference,
            title = book.title,
            author = book.author,
            progressPercentOverride = book.progressPercentOverride,
            dueText = book.dueText,
        )
        write(book.source, book.id, updated)
        preferences().edit()
            .putString("last.qualifiedId", qualifiedId(book.source, book.id))
            .putString("last.source", book.source.name)
            .putString("last.id", book.id)
            .putString("last.reference", book.playbackReference)
            .putString("last.title", book.title)
            .putString("last.author", book.author)
            .commit()
        mutableActiveAudiobook.value = lastActiveAudiobook()
        return updated
    }

    fun lastActiveQualifiedId(): String? = lastActiveAudiobook()?.qualifiedId

    fun clearLastActiveIfMatches(source: AudiobookSource, id: String) {
        if (lastActiveQualifiedId() != qualifiedId(source, id)) return
        preferences().edit()
            .remove("last.qualifiedId")
            .remove("last.source")
            .remove("last.id")
            .remove("last.reference")
            .remove("last.title")
            .remove("last.author")
            .commit()
        mutableActiveAudiobook.value = null
    }

    fun rememberedLibbyBooks(): List<Audiobook> = preferences()
        .getStringSet("remembered.Libby.ids", emptySet())
        .orEmpty()
        .mapNotNull { id ->
            val progress = read(AudiobookSource.Libby, id)
            if (progress.playbackReference.isBlank() || progress.title.isBlank()) return@mapNotNull null
            Audiobook(
                id = id,
                source = AudiobookSource.Libby,
                title = progress.title,
                author = progress.author,
                playbackReference = progress.playbackReference,
                durationMilliseconds = progress.durationMilliseconds,
                positionMilliseconds = progress.positionMilliseconds,
                playbackSpeed = progress.playbackSpeed,
                completed = progress.completed,
                lastPlayedAtMilliseconds = progress.lastPlayedAtMilliseconds,
                lastUpdatedAtMilliseconds = progress.lastUpdatedAtMilliseconds,
                progressPercentOverride = progress.progressPercentOverride,
                dueText = progress.dueText,
            )
        }

    fun saveMetadata(book: Audiobook) {
        val existing = read(book.source, book.id)
        write(
            book.source,
            book.id,
            existing.copy(
                playbackReference = book.playbackReference,
                title = book.title,
                author = book.author,
                progressPercentOverride = book.progressPercentOverride,
                dueText = book.dueText,
            ),
        )
    }

    /** A Ready shelf is authoritative; retain only loans present in that complete snapshot. */
    fun reconcileRememberedLibbyBooks(liveBooks: List<Audiobook>) {
        liveBooks.forEach(::saveMetadata)
        preferences().edit()
            .putStringSet("remembered.Libby.ids", liveBooks.map { it.id }.toSet())
            .commit()
    }

    /**
     * Immediately drop a single Libby loan from the remembered set, e.g. right after
     * a confirmed return. [reconcileRememberedLibbyBooks] is the normal way a stale id
     * gets pruned, but it only runs once shelf hydration has produced a couple of
     * stable scrapes -- if the app is closed (say, to install an update) before that
     * debounce fires, a just-returned loan's id is still sitting in
     * "remembered.Libby.ids" on the next launch and reappears on the Books screen
     * until a manual refresh forces hydration through again. Calling this right after
     * a successful return closes that window instead of waiting on it.
     */
    fun forgetLibbyBook(id: String) {
        val remaining = preferences().getStringSet("remembered.Libby.ids", emptySet())
            .orEmpty() - id
        preferences().edit()
            .putStringSet("remembered.Libby.ids", remaining)
            .commit()
        clearLastActiveIfMatches(AudiobookSource.Libby, id)
    }

    fun lastActiveAudiobook(): PersistedActiveAudiobook? {
        val preferences = preferences()
        val qualified = preferences.getString("last.qualifiedId", null)
            ?: migrateLegacyLastActive()
            ?: return null
        val source = preferences.getString("last.source", null)
            ?.let { runCatching { AudiobookSource.valueOf(it) }.getOrNull() }
            ?: AudiobookSource.entries.firstOrNull { qualified.startsWith("${it.name}:") }
            ?: return null
        val id = preferences.getString("last.id", null)
            ?.takeIf { it.isNotBlank() }
            ?: qualified.removePrefix("${source.name}:").takeIf { it.isNotBlank() }
            ?: return null
        val progress = read(source, id)
        return PersistedActiveAudiobook(
            source = source,
            id = id,
            playbackReference = preferences.getString("last.reference", null)
                ?: progress.playbackReference,
            title = preferences.getString("last.title", null) ?: progress.title,
            author = preferences.getString("last.author", null) ?: progress.author,
            progress = progress,
        )
    }

    fun recordSnapshot(book: Audiobook, state: PlayerState, force: Boolean = false) {
        latest[qualifiedId(book.source, book.id)] = book to state
        val now = System.currentTimeMillis()
        val key = qualifiedId(book.source, book.id)
        if (!force && now - (lastWrittenAt[key] ?: 0L) < SAVE_INTERVAL_MILLISECONDS) return
        persistSnapshot(book, state, now)
    }

    fun saveLocal(
        book: Audiobook,
        positionMilliseconds: Long,
        durationMilliseconds: Long,
        playbackSpeed: Float,
    ) {
        val state = PlayerState(
            title = book.title,
            positionSeconds = positionMilliseconds / 1000.0,
            durationSeconds = durationMilliseconds / 1000.0,
            playbackSpeed = playbackSpeed.toDouble(),
        )
        recordSnapshot(book, state, force = true)
    }

    fun flushLatest() {
        latest.values.forEach { (book, state) -> persistSnapshot(book, state, System.currentTimeMillis()) }
    }

    private fun persistSnapshot(book: Audiobook, state: PlayerState, now: Long) {
        val existing = read(book.source, book.id)
        val position = (state.positionSeconds * 1000).toLong().coerceAtLeast(0)
        val duration = (state.durationSeconds * 1000).toLong().coerceAtLeast(0)
        val completed = duration > 0 && duration - position.coerceAtMost(duration) <= 30_000L
        write(
            book.source,
            book.id,
            existing.copy(
                positionMilliseconds = position,
                durationMilliseconds = duration.takeIf { it > 0 } ?: existing.durationMilliseconds,
                playbackSpeed = state.playbackSpeed.toFloat().takeIf { it > 0 } ?: existing.playbackSpeed,
                completed = completed,
                lastUpdatedAtMilliseconds = now,
                playbackReference = book.playbackReference,
                title = book.title,
                author = book.author,
                progressPercentOverride = book.progressPercentOverride,
                dueText = book.dueText,
            ),
        )
        lastWrittenAt[qualifiedId(book.source, book.id)] = now
    }

    private fun write(source: AudiobookSource, id: String, progress: AudiobookProgress) {
        val key = qualifiedId(source, id)
        preferences().edit()
            .putLong("$key.position", progress.positionMilliseconds)
            .putLong("$key.duration", progress.durationMilliseconds)
            .putFloat("$key.speed", progress.playbackSpeed)
            .putBoolean("$key.completed", progress.completed)
            .putLong("$key.lastPlayed", progress.lastPlayedAtMilliseconds)
            .putLong("$key.updated", progress.lastUpdatedAtMilliseconds)
            .putString("$key.reference", progress.playbackReference)
            .putString("$key.title", progress.title)
            .putString("$key.author", progress.author)
            .putInt("$key.progressPercent", progress.progressPercentOverride ?: -1)
            .putString("$key.dueText", progress.dueText)
            .also { editor ->
                if (source == AudiobookSource.Libby) {
                    val ids = preferences().getStringSet("remembered.Libby.ids", emptySet())
                        .orEmpty() + id
                    editor.putStringSet("remembered.Libby.ids", ids)
                }
            }
            .commit()
    }

    private fun migrateLegacyLastActive(): String? {
        val preferences = preferences()
        val id = preferences.getString("last.id", null) ?: return null
        val source = runCatching {
            AudiobookSource.valueOf(preferences.getString("last.source", "").orEmpty())
        }.getOrNull() ?: return null
        return qualifiedId(source, id).also { qualified ->
            preferences.edit().putString("last.qualifiedId", qualified).commit()
        }
    }

    private fun preferences() =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
