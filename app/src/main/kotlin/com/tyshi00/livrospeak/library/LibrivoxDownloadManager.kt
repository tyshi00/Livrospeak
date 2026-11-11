package com.tyshi00.livrospeak.library

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

private const val TAG = "LibrivoxDownloads"
private const val LIBRIVOX_DOWNLOADS_DIRECTORY = "librivox_downloads"
private const val DOWNLOAD_PREFERENCES = "librivox_downloads"
private const val STATES_KEY = "states"
private const val MAX_ATTEMPTS = 3
private const val STORAGE_RESERVE_BYTES = 50L * 1024L * 1024L
private const val TEMP_ZIP_NAME = ".download.zip.part"

enum class LibrivoxDownloadStatus {
    NotDownloaded,
    Downloading,
    Extracting,
    Downloaded,
    Failed,
}

data class LibrivoxDownloadState(
    val bookId: String,
    val title: String = "",
    val author: String = "",
    val status: LibrivoxDownloadStatus = LibrivoxDownloadStatus.NotDownloaded,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val fileCount: Int = 0,
    val lastChapterIndex: Int = 0,
    val lastError: String = "",
    val lastUpdatedAtMilliseconds: Long = 0,
)

/**
 * Owns on-device storage for LibriVox downloads, and nothing else.
 *
 * Every LibriVox download lives under one fixed, app-private directory:
 * `filesDir/librivox_downloads/<book-id>/`. This directory is exclusive to this
 * manager -- local scanned books live in the public, MediaStore-indexed
 * `Audiobooks` folder ([LocalBookRepository]), and RSS downloads live in their
 * own separate `rss_downloads` directory ([RssDownloadManager]). Nothing outside
 * this one tree is ever read, written, or deleted here.
 *
 * LibriVox ships each book as a single zip of every chapter's MP3, hosted on
 * archive.org ([LibrivoxCatalogRepository]'s `url_zip_file`). [start] downloads
 * that zip into the book's own directory, extracts only its audio files directly
 * into that same directory, and deletes the zip -- so what's left on disk for a
 * finished download is just the per-chapter MP3s, nothing else.
 */
object LibrivoxDownloadManager {
    private lateinit var appContext: Context
    private lateinit var downloadsRoot: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val mutableStates = MutableStateFlow<Map<String, LibrivoxDownloadState>>(emptyMap())

    val states: StateFlow<Map<String, LibrivoxDownloadState>> = mutableStates.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        downloadsRoot = File(appContext.filesDir, LIBRIVOX_DOWNLOADS_DIRECTORY).apply { mkdirs() }
        mutableStates.value = readStates().mapValues { (bookId, state) -> reconcile(bookId, state) }
        persist()
    }

    fun stateFor(bookId: String): LibrivoxDownloadState? = mutableStates.value[bookId]

    /** Remembers which chapter [bookId] last played, so Play resumes there next time. */
    fun recordChapterIndex(bookId: String, index: Int) {
        val current = mutableStates.value[bookId] ?: return
        if (current.lastChapterIndex == index) return
        update(current.copy(lastChapterIndex = index))
    }

    /** True if any file for [bookId] has been downloaded. */
    fun hasDownload(bookId: String): Boolean =
        resolveBookDirectory(bookId)
            ?.let { it.isDirectory && it.listFiles()?.any { file -> file.isFile } == true }
            ?: false

    /** Total bytes currently used on disk by [bookId]'s download, 0 if none. */
    fun downloadedBytes(bookId: String): Long =
        resolveBookDirectory(bookId)
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L

    /** Absolute paths of every audio file downloaded for [bookId], in name order, or empty. */
    fun downloadedFiles(bookId: String): List<String> =
        resolveBookDirectory(bookId)
            ?.listFiles { file -> file.isFile && file.name.hasAudioExtension() }
            ?.sortedBy { it.name.lowercase() }
            ?.map { it.absolutePath }
            ?: emptyList()

    fun start(result: LibrivoxSearchResult) {
        if (result.zipUrl.isBlank()) return
        if (hasDownload(result.id) || jobs[result.id]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                downloadWithRetries(result)
            } finally {
                jobs.remove(result.id)
            }
        }
        val existing = jobs.putIfAbsent(result.id, job)
        if (existing == null) job.start() else job.cancel()
    }

    /**
     * Deletes every file downloaded for [bookId] and nothing else on the device,
     * and clears its persisted state.
     *
     * Safety, in order:
     * 1. [bookId] is resolved through [resolveBookDirectory], which strips the id
     *    down to a safe directory name (letters, digits, `-`, `_` only -- no `/`,
     *    no `.`, so path-traversal sequences like `../` can't even be constructed)
     *    and then double-checks the resulting path by comparing canonical paths,
     *    not strings. If that containment check fails for any reason, nothing is
     *    deleted.
     * 2. Only that single per-book subdirectory is ever a valid delete target.
     *    [downloadsRoot] itself is never a "direct child of itself," so it can
     *    never be the target, and nothing above it is ever touched.
     * 3. If there's nothing on disk for [bookId], this still clears any leftover
     *    state and returns true -- "no file left behind" is already satisfied.
     */
    suspend fun removeDownload(bookId: String): Boolean = withContext(Dispatchers.IO) {
        jobs.remove(bookId)?.cancelAndJoin()
        // Stop playback FIRST if this is the book actually playing right now --
        // deleting its chapter file out from under an open MediaPlayer doesn't
        // stop it, it just keeps playing whatever's already buffered with no
        // indication anything broke. Checked before the delete below so there's
        // no window where the player is still "open" on a file that's already gone.
        if (LibrivoxPlaybackCoordinator.currentBookId() == bookId) {
            LibrivoxPlaybackCoordinator.stop()
        }
        val deleted = wipeBookDirectory(bookId)
        if (deleted) {
            mutableStates.value = mutableStates.value - bookId
            persist()
        }
        deleted
    }

    private suspend fun downloadWithRetries(result: LibrivoxSearchResult) {
        var finalReason = "Download failed"
        repeat(MAX_ATTEMPTS) { attempt ->
            val outcome = runCatching { downloadAndExtractOnce(result) }
            if (outcome.isSuccess) return
            val failure = outcome.exceptionOrNull()
            if (failure is CancellationException) throw failure
            finalReason = (failure as? LibrivoxDownloadException)?.reason ?: "Download failed"
            wipeBookDirectory(result.id)
            if (finalReason == "Not enough storage") {
                update(failedState(result, finalReason))
                return
            }
            if (attempt < MAX_ATTEMPTS - 1) delay((attempt + 1) * 1_500L)
        }
        update(failedState(result, finalReason))
    }

    private fun downloadAndExtractOnce(result: LibrivoxSearchResult) {
        val bookId = result.id
        val safeName = sanitize(bookId)
        if (safeName.isBlank()) throw LibrivoxDownloadException("Download failed")
        val bookDir = File(downloadsRoot, safeName).apply { mkdirs() }
        val tempZip = File(bookDir, TEMP_ZIP_NAME)

        update(
            initialState(result).copy(
                status = LibrivoxDownloadStatus.Downloading,
                lastUpdatedAtMilliseconds = System.currentTimeMillis(),
            ),
        )

        val connection = (URL(result.zipUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Livrospeak/Android")
        }
        try {
            if (connection.responseCode !in 200..299) throw LibrivoxDownloadException("Download failed")
            val totalBytes = connection.contentLengthLong.coerceAtLeast(0)
            if (totalBytes > 0 && availableBytes() - STORAGE_RESERVE_BYTES < totalBytes) {
                throw LibrivoxDownloadException("Not enough storage")
            }

            connection.inputStream.use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var lastPublished = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (availableBytes() <= STORAGE_RESERVE_BYTES) {
                            throw LibrivoxDownloadException("Not enough storage")
                        }
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastPublished >= 512 * 1024) {
                            update(
                                initialState(result).copy(
                                    status = LibrivoxDownloadStatus.Downloading,
                                    bytesDownloaded = downloaded,
                                    totalBytes = totalBytes,
                                    lastUpdatedAtMilliseconds = System.currentTimeMillis(),
                                ),
                            )
                            lastPublished = downloaded
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (!tempZip.isFile || tempZip.length() <= 0) throw LibrivoxDownloadException("Download failed")

        update(
            initialState(result).copy(
                status = LibrivoxDownloadStatus.Extracting,
                bytesDownloaded = tempZip.length(),
                totalBytes = tempZip.length(),
                lastUpdatedAtMilliseconds = System.currentTimeMillis(),
            ),
        )

        val extractedCount = extractAudioEntries(tempZip, bookDir)
        tempZip.delete()
        if (extractedCount == 0) throw LibrivoxDownloadException("No audio files in download")

        update(
            initialState(result).copy(
                status = LibrivoxDownloadStatus.Downloaded,
                bytesDownloaded = downloadedBytes(bookId),
                totalBytes = downloadedBytes(bookId),
                fileCount = extractedCount,
                lastUpdatedAtMilliseconds = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Extracts only audio entries from [zipFile] directly into [bookDir], flat
     * (any nested path in the zip entry's name is dropped, so what lands on disk
     * is always a direct child of [bookDir]). Every output path is additionally
     * verified by canonical-path containment before it's written, the same
     * belt-and-suspenders check [resolveBookDirectory] uses -- so a zip entry
     * engineered to escape [bookDir] (a "zip-slip" entry) is refused rather than
     * silently written somewhere else on the device.
     */
    private fun extractAudioEntries(zipFile: File, bookDir: File): Int {
        var extracted = 0
        val canonicalBookDir = bookDir.canonicalFile
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val flatName = entry.name.substringAfterLast('/').trim()
                    if (flatName.isNotBlank() && flatName != ".." && flatName.hasAudioExtension()) {
                        val outFile = File(bookDir, flatName).canonicalFile
                        if (outFile.parentFile == canonicalBookDir) {
                            outFile.outputStream().use { out -> zis.copyTo(out) }
                            extracted++
                        } else {
                            Log.w(TAG, "extractAudioEntries skipped entry that escaped book directory")
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return extracted
    }

    /** Deletes [bookId]'s entire directory tree, or returns true if there was nothing there. */
    private fun wipeBookDirectory(bookId: String): Boolean {
        val target = resolveBookDirectory(bookId) ?: return false
        if (!target.exists()) return true
        val deleted = target.deleteRecursively()
        Log.d(TAG, "wipeBookDirectory idHash=${bookId.hashCode()} deleted=$deleted")
        return deleted && !target.exists()
    }

    /**
     * Resolves [bookId] to its per-book directory under [downloadsRoot], or null
     * if the id is blank after sanitizing or the resolved path would land
     * anywhere other than a direct child of [downloadsRoot]. Never creates the
     * directory.
     */
    private fun resolveBookDirectory(bookId: String): File? {
        val safeName = sanitize(bookId)
        if (safeName.isBlank()) return null
        val root = downloadsRoot.canonicalFile
        val candidate = File(downloadsRoot, safeName).canonicalFile
        return candidate.takeIf { it.parentFile == root }
    }

    private fun sanitize(bookId: String): String =
        bookId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(128)

    private fun availableBytes(): Long = StatFs(downloadsRoot.absolutePath).availableBytes

    private fun initialState(result: LibrivoxSearchResult) =
        mutableStates.value[result.id] ?: LibrivoxDownloadState(
            bookId = result.id,
            title = result.title,
            author = result.author,
        )

    private fun failedState(result: LibrivoxSearchResult, reason: String) =
        initialState(result).copy(
            status = LibrivoxDownloadStatus.Failed,
            lastError = reason,
            lastUpdatedAtMilliseconds = System.currentTimeMillis(),
        )

    private fun reconcile(bookId: String, state: LibrivoxDownloadState): LibrivoxDownloadState = when {
        state.status == LibrivoxDownloadStatus.Downloaded && !hasDownload(bookId) -> state.copy(
            status = LibrivoxDownloadStatus.Failed,
            lastError = "Download missing",
            lastUpdatedAtMilliseconds = System.currentTimeMillis(),
        )
        state.status == LibrivoxDownloadStatus.Downloading || state.status == LibrivoxDownloadStatus.Extracting -> {
            // The process died mid-download/extract last time; nothing resumable
            // is left in a trustworthy state, so clear the partial files and
            // reset rather than silently resuming into possibly-truncated audio.
            wipeBookDirectory(bookId)
            state.copy(status = LibrivoxDownloadStatus.NotDownloaded, bytesDownloaded = 0, totalBytes = 0)
        }
        else -> state
    }

    @Synchronized
    private fun update(state: LibrivoxDownloadState) {
        mutableStates.value = mutableStates.value + (state.bookId to state)
        persist()
    }

    @Synchronized
    private fun persist() {
        val json = JSONArray()
        mutableStates.value.values.forEach { state ->
            json.put(
                JSONObject()
                    .put("bookId", state.bookId)
                    .put("title", state.title)
                    .put("author", state.author)
                    .put("status", state.status.name)
                    .put("bytes", state.bytesDownloaded)
                    .put("total", state.totalBytes)
                    .put("fileCount", state.fileCount)
                    .put("lastChapterIndex", state.lastChapterIndex)
                    .put("error", state.lastError)
                    .put("updated", state.lastUpdatedAtMilliseconds),
            )
        }
        preferences().edit().putString(STATES_KEY, json.toString()).commit()
    }

    private fun readStates(): Map<String, LibrivoxDownloadState> = runCatching {
        val json = JSONArray(preferences().getString(STATES_KEY, "[]"))
        buildMap {
            repeat(json.length()) { index ->
                val item = json.getJSONObject(index)
                val id = item.getString("bookId")
                put(
                    id,
                    LibrivoxDownloadState(
                        bookId = id,
                        title = item.optString("title"),
                        author = item.optString("author"),
                        status = runCatching {
                            LibrivoxDownloadStatus.valueOf(item.optString("status"))
                        }.getOrDefault(LibrivoxDownloadStatus.NotDownloaded),
                        bytesDownloaded = item.optLong("bytes"),
                        totalBytes = item.optLong("total"),
                        fileCount = item.optInt("fileCount"),
                        lastChapterIndex = item.optInt("lastChapterIndex"),
                        lastError = item.optString("error"),
                        lastUpdatedAtMilliseconds = item.optLong("updated"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    private fun preferences() =
        appContext.getSharedPreferences(DOWNLOAD_PREFERENCES, Context.MODE_PRIVATE)
}

private class LibrivoxDownloadException(val reason: String) : Exception(reason)

private fun String.hasAudioExtension(): Boolean =
    endsWith(".mp3", ignoreCase = true) ||
        endsWith(".m4a", ignoreCase = true) ||
        endsWith(".m4b", ignoreCase = true)
