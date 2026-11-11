package com.tyshi00.livrospeak.library

import com.tyshi00.livrospeak.player.LocalPlaybackController

/**
 * Sequences a LibriVox book's chapter files as one continuous listen.
 *
 * LibriVox books download as one MP3 per chapter ([LibrivoxDownloadManager]).
 * [LocalPlaybackController] only ever plays one file at a time and has no
 * concept of a multi-file book, so this coordinator sits above it: it opens
 * chapter files one at a time through the ordinary single-file player, and
 * advances to the next chapter when [LocalPlaybackController]'s completion
 * callback fires. This is original sequencing logic written for LibriVox
 * specifically -- it does not change [LocalPlaybackController]'s own
 * architecture (its hook is a single optional, default-null callback
 * parameter), and nothing here is ported from the embedded-chapter feature
 * that was removed earlier.
 */
object LibrivoxPlaybackCoordinator {
    private var activeBookId: String? = null
    private var chapterFiles: List<String> = emptyList()
    private var title: String = ""
    private var author: String = ""

    /**
     * Starts (or resumes) playback of [bookId]'s downloaded chapters, beginning
     * at its last-played chapter ([LibrivoxDownloadManager]'s persisted state).
     * Calls [onChapterChanged] once immediately with the chapter now opening,
     * and again every time playback automatically advances to the next chapter
     * -- the caller uses this to keep the visible "now playing" book in sync.
     * A no-op if [bookId] has no downloaded files.
     */
    fun play(
        bookId: String,
        title: String,
        author: String,
        autoPlay: Boolean = true,
        onChapterChanged: (Audiobook) -> Unit,
    ) {
        val files = LibrivoxDownloadManager.downloadedFiles(bookId)
        if (files.isEmpty()) return
        activeBookId = bookId
        chapterFiles = files
        this.title = title
        this.author = author
        val startIndex = (LibrivoxDownloadManager.stateFor(bookId)?.lastChapterIndex ?: 0)
            .coerceIn(0, files.lastIndex)
        openChapter(startIndex, autoPlay, onChapterChanged)
    }

    /**
     * Jumps to chapter [index] of [bookId] and plays it from its beginning, then
     * carries on advancing chapter to chapter from there like [play]. Used by the
     * player's Chapter Selection screen. A no-op if [index] isn't one of the
     * book's downloaded chapters.
     */
    fun playChapter(
        bookId: String,
        title: String,
        author: String,
        index: Int,
        onChapterChanged: (Audiobook) -> Unit,
    ) {
        val files = LibrivoxDownloadManager.downloadedFiles(bookId)
        if (index !in files.indices) return
        activeBookId = bookId
        chapterFiles = files
        this.title = title
        this.author = author
        // LocalPlaybackController.open() resumes a file from its saved position.
        // Picking a chapter from the list means "start this chapter", so its saved
        // position is reset first; its saved length is kept, and openChapter sets
        // its speed.
        val saved = AudiobookProgressStore.read(AudiobookSource.Librivox, chapterId(bookId, index))
        AudiobookProgressStore.saveLocal(
            book = chapterBook(bookId, index, files),
            positionMilliseconds = 0,
            durationMilliseconds = saved.durationMilliseconds,
            playbackSpeed = saved.playbackSpeed,
        )
        openChapter(index, autoPlay = true, onChapterChanged)
    }

    /**
     * The speed [bookId] is being listened at, so the next chapter opened keeps it.
     * Speed is saved per audio file, and each chapter is its own file, so without
     * this every new chapter would start at 1x. Taken from the player when one of
     * this book's chapters is loaded there (that is the live setting), otherwise
     * from the chapter the book was last left on.
     */
    private fun bookSpeed(bookId: String): Float {
        val loaded = LocalPlaybackController.currentBook()
        if (loaded != null && loaded.source == AudiobookSource.Librivox && bookIdOf(loaded.id) == bookId) {
            return LocalPlaybackController.state.value.playbackSpeed.toFloat()
        }
        val lastIndex = LibrivoxDownloadManager.stateFor(bookId)?.lastChapterIndex ?: 0
        return AudiobookProgressStore.read(AudiobookSource.Librivox, chapterId(bookId, lastIndex)).playbackSpeed
    }

    /**
     * The LibriVox book whose chapter is loaded in [LocalPlaybackController] right
     * now (playing or paused), or null. Asking the player instead of only
     * remembering the last book opened here matters: a Local or RSS book opened
     * afterwards takes over that same player, and this must not keep claiming
     * the earlier LibriVox book is still the one loaded.
     */
    fun currentBookId(): String? {
        val bookId = activeBookId ?: return null
        val loaded = LocalPlaybackController.currentBook() ?: return null
        return bookId.takeIf {
            loaded.source == AudiobookSource.Librivox && bookIdOf(loaded.id) == bookId
        }
    }

    /** Stops playback and forgets the active book. Used when the book currently
     *  loaded is being removed: [LocalPlaybackController.close] saves final
     *  progress, releases the MediaPlayer, and clears the playback notification.
     *  Without it, deleting the chapter file left the player open on a file that
     *  no longer exists, still playing whatever was buffered. */
    fun stop() {
        activeBookId = null
        chapterFiles = emptyList()
        LocalPlaybackController.close()
    }

    /** A chapter's [Audiobook.id] is "bookId:chapterIndex"; these take it apart. */
    fun bookIdOf(audiobookId: String): String = audiobookId.substringBefore(':')

    fun chapterIndexOf(audiobookId: String): Int? =
        audiobookId.substringAfter(':', "").toIntOrNull()

    private fun chapterId(bookId: String, index: Int): String = "$bookId:$index"

    private fun chapterBook(bookId: String, index: Int, files: List<String>): Audiobook {
        val chapterCount = files.size
        return Audiobook(
            id = chapterId(bookId, index),
            source = AudiobookSource.Librivox,
            title = title,
            author = if (chapterCount > 1) {
                "${author.ifBlank { "LibriVox" }} — Chapter ${index + 1} of $chapterCount"
            } else {
                author
            },
            playbackReference = files[index],
        )
    }

    private fun openChapter(index: Int, autoPlay: Boolean, onChapterChanged: (Audiobook) -> Unit) {
        val bookId = activeBookId ?: return
        val files = chapterFiles
        if (index !in files.indices) return
        // Read before recordChapterIndex below moves the book's "last chapter" on.
        val speed = bookSpeed(bookId)
        LibrivoxDownloadManager.recordChapterIndex(bookId, index)
        val book = chapterBook(bookId, index, files)
        // LocalPlaybackController.open() takes a file's speed from its saved
        // progress, so the book's speed is written there first. The chapter's own
        // saved position and length are passed back unchanged.
        val saved = AudiobookProgressStore.read(AudiobookSource.Librivox, book.id)
        if (saved.playbackSpeed != speed) {
            AudiobookProgressStore.saveLocal(
                book = book,
                positionMilliseconds = saved.positionMilliseconds,
                durationMilliseconds = saved.durationMilliseconds,
                playbackSpeed = speed,
            )
        }
        onChapterChanged(book)
        LocalPlaybackController.open(book, autoPlay = autoPlay) {
            val nextIndex = index + 1
            if (activeBookId == bookId && nextIndex in chapterFiles.indices) {
                openChapter(nextIndex, autoPlay = true, onChapterChanged)
            }
        }
    }
}
