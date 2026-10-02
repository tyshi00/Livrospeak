package com.tyshi00.livrospeak.library

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val DOWNLOAD_PREFERENCES = "rss_downloads"
private const val DOWNLOADS_KEY = "downloads"
private const val MAX_REDIRECTS = 5
private const val MAX_ATTEMPTS = 3
private const val STORAGE_RESERVE_BYTES = 50L * 1024L * 1024L

enum class RssDownloadStatus {
    NotDownloaded,
    Downloading,
    Downloaded,
    Failed,
}

data class RssDownloadState(
    val audiobookId: String,
    val feedId: String = "",
    val enclosureHash: String,
    val status: RssDownloadStatus = RssDownloadStatus.NotDownloaded,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val localReference: String = "",
    val lastError: String = "",
    val lastUpdatedAtMilliseconds: Long = 0,
)

/** Durable, app-private full-file downloads for RSS audiobooks. */
object RssDownloadManager {
    private lateinit var appContext: Context
    private lateinit var downloadDirectory: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val mutableStates = MutableStateFlow<Map<String, RssDownloadState>>(emptyMap())

    val states: StateFlow<Map<String, RssDownloadState>> = mutableStates.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        downloadDirectory = File(appContext.filesDir, "rss_downloads").apply { mkdirs() }
        mutableStates.value = readStates().mapValues { (_, state) -> reconcile(state) }
        persist()
    }

    fun stateFor(audiobookId: String): RssDownloadState? = mutableStates.value[audiobookId]

    fun verifiedLocalReference(book: Audiobook): String? {
        val state = stateFor(book.id) ?: return null
        if (state.enclosureHash != hash(book.playbackReference)) return null
        if (state.status != RssDownloadStatus.Downloaded) return null
        val file = state.localReference.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        return file.absolutePath.takeIf {
            file.isFile && file.length() > 0 &&
                (state.totalBytes <= 0 || file.length() == state.totalBytes)
        }
    }

    /** Actual durable file size; never trusts a remote Content-Length value. */
    fun downloadedSizeBytes(book: Audiobook): Long =
        verifiedLocalReference(book)?.let(::File)?.length()?.coerceAtLeast(0) ?: 0L

    fun start(book: Audiobook, feedId: String) {
        if (book.source != AudiobookSource.Rss || !isHttpUrl(book.playbackReference)) return
        if (verifiedLocalReference(book) != null || jobs[book.id]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                downloadWithRetries(book, feedId)
            } finally {
                jobs.remove(book.id)
            }
        }
        val existing = jobs.putIfAbsent(book.id, job)
        if (existing == null) job.start() else job.cancel()
    }

    suspend fun removeDownload(book: Audiobook) {
        jobs.remove(book.id)?.cancelAndJoin()
        completedFile(book).delete()
        partialFile(book).delete()
        val existing = stateFor(book.id) ?: initialState(book, "")
        update(
            existing.copy(
                status = RssDownloadStatus.NotDownloaded,
                bytesDownloaded = 0,
                totalBytes = 0,
                localReference = "",
                lastError = "",
                lastUpdatedAtMilliseconds = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun downloadWithRetries(book: Audiobook, feedId: String) {
        var finalReason = "Download failed"
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = runCatching { downloadOnce(book, feedId) }
            if (result.isSuccess) return
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            finalReason = when ((result.exceptionOrNull() as? DownloadException)?.reason) {
                DownloadFailure.NotEnoughStorage -> "Not enough storage"
                else -> "Download failed"
            }
            if (finalReason == "Not enough storage") {
                update(
                    (stateFor(book.id) ?: initialState(book, feedId)).copy(
                        status = RssDownloadStatus.Failed,
                        lastError = finalReason,
                        lastUpdatedAtMilliseconds = System.currentTimeMillis(),
                    ),
                )
                return
            }
            if (attempt < MAX_ATTEMPTS - 1) delay((attempt + 1) * 1_500L)
        }
        update(
            stateFor(book.id)?.copy(
                status = RssDownloadStatus.Failed,
                lastError = finalReason,
                lastUpdatedAtMilliseconds = System.currentTimeMillis(),
            ) ?: initialState(book, feedId).copy(
                status = RssDownloadStatus.Failed,
                lastError = finalReason,
                lastUpdatedAtMilliseconds = System.currentTimeMillis(),
            ),
        )
    }

    private fun downloadOnce(book: Audiobook, feedId: String) {
        val completed = completedFile(book)
        val partial = partialFile(book)
        val enclosureHash = hash(book.playbackReference)
        if (stateFor(book.id)?.enclosureHash?.takeIf { it.isNotBlank() } != null &&
            stateFor(book.id)?.enclosureHash != enclosureHash
        ) {
            partial.delete()
            completed.delete()
        }
        val existingBytes = partial.length().coerceAtLeast(0)
        update(
            initialState(book, feedId).copy(
                status = RssDownloadStatus.Downloading,
                bytesDownloaded = existingBytes,
                lastUpdatedAtMilliseconds = System.currentTimeMillis(),
            ),
        )

        var currentUrl = book.playbackReference
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!isHttpUrl(currentUrl)) throw DownloadException(DownloadFailure.Network)
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Livrospeak/Android")
                if (existingBytes > 0) setRequestProperty("Range", "bytes=$existingBytes-")
            }
            try {
                val statusCode = connection.responseCode
                if (statusCode in 300..399) {
                    if (redirectCount == MAX_REDIRECTS) throw DownloadException(DownloadFailure.Network)
                    currentUrl = URL(
                        URL(currentUrl),
                        connection.getHeaderField("Location") ?: throw DownloadException(DownloadFailure.Network),
                    ).toString()
                    return@repeat
                }
                if (statusCode !in 200..299) throw DownloadException(DownloadFailure.Network)

                val append = existingBytes > 0 && statusCode == HttpURLConnection.HTTP_PARTIAL
                if (existingBytes > 0 && !append) partial.delete()
                val startingBytes = if (append) existingBytes else 0L
                val responseBytes = connection.contentLengthLong.coerceAtLeast(0)
                val totalBytes = if (responseBytes > 0) startingBytes + responseBytes else 0L
                if (totalBytes > 0 && availableBytes() - STORAGE_RESERVE_BYTES < totalBytes - startingBytes) {
                    throw DownloadException(DownloadFailure.NotEnoughStorage)
                }

                connection.inputStream.use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = startingBytes
                        var lastPublished = downloaded
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (availableBytes() <= STORAGE_RESERVE_BYTES) {
                                throw DownloadException(DownloadFailure.NotEnoughStorage)
                            }
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastPublished >= 512 * 1024) {
                                update(
                                    initialState(book, feedId).copy(
                                        status = RssDownloadStatus.Downloading,
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
                if (!partial.isFile || partial.length() <= 0) throw DownloadException(DownloadFailure.Network)
                if (completed.exists()) completed.delete()
                if (!partial.renameTo(completed)) throw DownloadException(DownloadFailure.Storage)
                update(
                    initialState(book, feedId).copy(
                        status = RssDownloadStatus.Downloaded,
                        bytesDownloaded = completed.length(),
                        totalBytes = completed.length(),
                        localReference = completed.absolutePath,
                        lastUpdatedAtMilliseconds = System.currentTimeMillis(),
                    ),
                )
                return
            } finally {
                connection.disconnect()
            }
        }
        throw DownloadException(DownloadFailure.Network)
    }

    private fun initialState(book: Audiobook, feedId: String) = RssDownloadState(
        audiobookId = book.id,
        feedId = feedId,
        enclosureHash = hash(book.playbackReference),
        bytesDownloaded = partialFile(book).length().coerceAtLeast(0),
    )

    private fun reconcile(state: RssDownloadState): RssDownloadState {
        val completed = state.localReference.takeIf { it.isNotBlank() }?.let(::File)
        return when {
            state.status == RssDownloadStatus.Downloaded &&
                (completed == null || !completed.isFile || completed.length() <= 0 ||
                    (state.totalBytes > 0 && completed.length() != state.totalBytes)) -> state.copy(
                status = RssDownloadStatus.Failed,
                localReference = "",
                lastError = "Download missing",
                lastUpdatedAtMilliseconds = System.currentTimeMillis(),
            )
            state.status == RssDownloadStatus.Downloading -> state.copy(
                status = RssDownloadStatus.NotDownloaded,
                bytesDownloaded = partialFile(state.audiobookId, state.enclosureHash).length(),
            )
            else -> state
        }
    }

    private fun completedFile(book: Audiobook) =
        File(downloadDirectory, "${hash(book.id)}.${extension(book.playbackReference)}")

    private fun partialFile(book: Audiobook) =
        File(downloadDirectory, "${hash(book.id)}.${extension(book.playbackReference)}.part")

    private fun partialFile(id: String, enclosureHash: String) =
        downloadDirectory.listFiles()?.firstOrNull {
            it.name.startsWith(hash(id)) && it.name.endsWith(".part")
        } ?: File(downloadDirectory, "${hash(id)}.${enclosureHash.take(3)}.part")

    private fun extension(reference: String): String =
        Uri.parse(reference).path.orEmpty().substringAfterLast('.', "mp3")
            .lowercase(Locale.US).takeIf { it in setOf("mp3", "m4b") } ?: "mp3"

    private fun availableBytes(): Long = StatFs(downloadDirectory.absolutePath).availableBytes

    @Synchronized
    private fun update(state: RssDownloadState) {
        mutableStates.value = mutableStates.value + (state.audiobookId to state)
        persist()
    }

    @Synchronized
    private fun persist() {
        val json = JSONArray()
        mutableStates.value.values.forEach { state ->
            json.put(
                JSONObject()
                    .put("id", state.audiobookId)
                    .put("feedId", state.feedId)
                    .put("enclosureHash", state.enclosureHash)
                    .put("status", state.status.name)
                    .put("bytes", state.bytesDownloaded)
                    .put("total", state.totalBytes)
                    .put("localReference", state.localReference)
                    .put("error", state.lastError)
                    .put("updated", state.lastUpdatedAtMilliseconds),
            )
        }
        preferences().edit().putString(DOWNLOADS_KEY, json.toString()).commit()
    }

    private fun readStates(): Map<String, RssDownloadState> = runCatching {
        val json = JSONArray(preferences().getString(DOWNLOADS_KEY, "[]"))
        buildMap {
            repeat(json.length()) { index ->
                val item = json.getJSONObject(index)
                val id = item.getString("id")
                put(
                    id,
                    RssDownloadState(
                        audiobookId = id,
                        feedId = item.optString("feedId"),
                        enclosureHash = item.optString("enclosureHash"),
                        status = runCatching {
                            RssDownloadStatus.valueOf(item.optString("status"))
                        }.getOrDefault(RssDownloadStatus.NotDownloaded),
                        bytesDownloaded = item.optLong("bytes"),
                        totalBytes = item.optLong("total"),
                        localReference = item.optString("localReference"),
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

private enum class DownloadFailure { Network, Storage, NotEnoughStorage }
private class DownloadException(val reason: DownloadFailure) : Exception()

private fun isHttpUrl(value: String): Boolean {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
    return uri.scheme?.lowercase(Locale.US) in setOf("http", "https") && !uri.host.isNullOrBlank()
}

private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
