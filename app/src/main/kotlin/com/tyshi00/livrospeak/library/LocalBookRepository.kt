package com.tyshi00.livrospeak.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val TAG = "LocalBooks"
private const val AUDIOBOOKS_DIRECTORY = "Audiobooks"

sealed interface LocalScanResult {
    data object PermissionRequired : LocalScanResult
    data object FolderMissing : LocalScanResult
    data object Empty : LocalScanResult
    data class Success(val books: List<Audiobook>) : LocalScanResult
}

object LocalBookRepository {
    private lateinit var appContext: Context
    private val scanMutex = Mutex()
    private val mutableBooks = MutableStateFlow<List<Audiobook>>(emptyList())
    private val mutableScanResult = MutableStateFlow<LocalScanResult?>(null)
    private val mutableScanning = MutableStateFlow(false)
    private var scanGeneration = 0

    val books: StateFlow<List<Audiobook>> = mutableBooks.asStateFlow()
    val scanResult: StateFlow<LocalScanResult?> = mutableScanResult.asStateFlow()
    val scanning: StateFlow<Boolean> = mutableScanning.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun hasReadPermission(): Boolean =
        appContext.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED

    fun updateBook(book: Audiobook) {
        mutableBooks.update { current ->
            current.map { existing -> if (existing.id == book.id) book else existing }
        }
    }

    fun publishPermissionRequired() {
        mutableScanResult.value = LocalScanResult.PermissionRequired
    }

    suspend fun deleteBook(book: Audiobook): Boolean = withContext(Dispatchers.IO) {
        if (book.source != AudiobookSource.Local) return@withContext false
        val uri = runCatching { Uri.parse(book.playbackReference) }.getOrNull()
            ?: return@withContext false
        val deleted = runCatching { appContext.contentResolver.delete(uri, null, null) > 0 }
            .getOrDefault(false)
        if (deleted) scan()
        deleted
    }

    suspend fun scan(): LocalScanResult {
        if (!scanMutex.tryLock()) return mutableScanResult.value ?: LocalScanResult.Empty
        mutableScanning.value = true
        val generation = ++scanGeneration
        Log.d(TAG, "scan generation=$generation started")
        return try {
            val result = scanFresh(generation)
            val snapshot = (result as? LocalScanResult.Success)?.books.orEmpty()
            mutableBooks.value = snapshot
            mutableScanResult.value = result
            Log.d(TAG, "repository snapshot count=${snapshot.size}")
            result
        } finally {
            mutableScanning.value = false
            scanMutex.unlock()
        }
    }

    private suspend fun scanFresh(generation: Int): LocalScanResult = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext LocalScanResult.PermissionRequired

        // MediaStore does not represent empty directories. This existence-only probe lets Livrospeak
        // distinguish a missing top-level folder while all file discovery remains in MediaStore.
        val folderExists = File(
            Environment.getExternalStorageDirectory(),
            AUDIOBOOKS_DIRECTORY,
        ).isDirectory
        val directCandidates = if (folderExists) {
            File(Environment.getExternalStorageDirectory(), AUDIOBOOKS_DIRECTORY)
                .listFiles()
                ?.filter { file ->
                    file.isFile &&
                        !file.name.startsWith('.') &&
                        file.name.hasSupportedExtension()
                }
        } else {
            emptyList()
        }
        val directlyReadable = directCandidates != null
        val scannedUris = withTimeoutOrNull(10_000) {
            directCandidates?.let { requestMediaIndexing(it) }.orEmpty()
        }.orEmpty()
        Log.d(TAG, "scan generation=$generation strategy=direct-index-and-mediastore")

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            if (Build.VERSION.SDK_INT < 29) add(MediaStore.Audio.Media.DATA)
        }.toTypedArray()
        val selection: String
        val arguments: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
            arguments = arrayOf("$AUDIOBOOKS_DIRECTORY/")
        } else {
            val root = Environment.getExternalStorageDirectory().absolutePath
            selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
            arguments = arrayOf("$root/$AUDIOBOOKS_DIRECTORY/%")
        }

        var mp3Count = 0
        var m4bCount = 0
        val books = try {
            val accepted = linkedMapOf<String, Audiobook>()
            val directNames = directCandidates?.map { it.name.lowercase() }?.toSet().orEmpty()
            buildList {
                appContext.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    arguments,
                    "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC",
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    while (cursor.moveToNext()) {
                        val displayName = cursor.getString(nameColumn).orEmpty()
                        val mimeType = cursor.getString(mimeColumn).orEmpty()
                        val dataPath = if (dataColumn >= 0) cursor.getString(dataColumn).orEmpty() else ""
                        if (displayName.startsWith('.') || dataPath.substringAfterLast('/').startsWith('.')) continue
                        if (directlyReadable && displayName.lowercase() !in directNames) continue
                        val isMp3 = displayName.endsWith(".mp3", ignoreCase = true) || mimeType == "audio/mpeg"
                        val isM4b = displayName.endsWith(".m4b", ignoreCase = true)
                        val isM4a = displayName.endsWith(".m4a", ignoreCase = true)
                        if (!isMp3 && !isM4b && !isM4a) continue
                        if (isM4b || isM4a) m4bCount++ else mp3Count++
                        if (Build.VERSION.SDK_INT < 29) {
                            val directParent = File(dataPath).parentFile?.name
                            if (directParent != AUDIOBOOKS_DIRECTORY) continue
                        }

                        val mediaId = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(collection, mediaId)
                        val stableId = "external:$mediaId"
                        val embedded = readMetadata(uri)
                        recordGenreIfNeeded(stableId, embedded.genre)
                        val stored = AudiobookProgressStore.read(AudiobookSource.Local, stableId)
                        val duration = embedded.durationMilliseconds.takeIf { it > 0 }
                            ?: stored.durationMilliseconds.takeIf { it > 0 }
                            ?: 0L
                        accepted[stableId] = audiobookFrom(
                            stableId = stableId,
                            displayName = displayName,
                            uri = uri,
                            embedded = embedded,
                            stored = stored,
                            duration = duration,
                            fileSizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0),
                        )
                    }
                }
                scannedUris.forEach { (displayName, uri) ->
                    val mediaId = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return@forEach
                    val stableId = "external:$mediaId"
                    if (stableId in accepted) return@forEach
                    val embedded = readMetadata(uri)
                    recordGenreIfNeeded(stableId, embedded.genre)
                    val stored = AudiobookProgressStore.read(AudiobookSource.Local, stableId)
                    val duration = embedded.durationMilliseconds.takeIf { it > 0 }
                        ?: stored.durationMilliseconds.takeIf { it > 0 }
                        ?: 0L
                    if (displayName.endsWith(".m4b", true) || displayName.endsWith(".m4a", true)) {
                        m4bCount++
                    } else {
                        mp3Count++
                    }
                    accepted[stableId] = audiobookFrom(
                        stableId = stableId,
                        displayName = displayName,
                        uri = uri,
                        embedded = embedded,
                        stored = stored,
                        duration = duration,
                        fileSizeBytes = querySize(uri),
                    )
                }
                addAll(accepted.values)
            }.sortedBy { it.title.lowercase() }
        } catch (_: SecurityException) {
            return@withContext LocalScanResult.PermissionRequired
        } catch (_: Exception) {
            return@withContext if (folderExists) LocalScanResult.Empty else LocalScanResult.FolderMissing
        }

        Log.d(TAG, "audiobooks folder found=$folderExists")
        Log.d(TAG, "raw candidate count=${directCandidates?.size ?: books.size}")
        Log.d(TAG, "local book count=${books.size}")
        Log.d(TAG, "mp3 count=$mp3Count")
        Log.d(TAG, "m4b count=$m4bCount")
        when {
            books.isNotEmpty() -> LocalScanResult.Success(books)
            !folderExists -> LocalScanResult.FolderMissing
            else -> LocalScanResult.Empty
        }
    }

    private suspend fun requestMediaIndexing(files: List<File>): Map<String, Uri> {
        if (files.isEmpty()) return emptyMap()
        return suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(files.size)
            val indexed = ConcurrentHashMap<String, Uri>()
            MediaScannerConnection.scanFile(
                appContext,
                files.map { it.absolutePath }.toTypedArray(),
                files.map { if (it.name.endsWith(".m4b", true)) "audio/mp4" else "audio/mpeg" }
                    .toTypedArray(),
            ) { path, uri ->
                if (uri != null) indexed[File(path).name] = uri
                if (remaining.decrementAndGet() == 0 && continuation.isActive) {
                    continuation.resume(indexed.toMap())
                }
            }
        }
    }

    private fun audiobookFrom(
        stableId: String,
        displayName: String,
        uri: Uri,
        embedded: EmbeddedMetadata,
        stored: AudiobookProgress,
        duration: Long,
        fileSizeBytes: Long,
    ): Audiobook = Audiobook(
        id = stableId,
        source = AudiobookSource.Local,
        title = embedded.title.ifBlank { displayName.removeSupportedSuffix() },
        author = embedded.author,
        playbackReference = uri.toString(),
        durationMilliseconds = duration,
        positionMilliseconds = stored.positionMilliseconds.coerceAtMost(
            duration.takeIf { it > 0 } ?: Long.MAX_VALUE,
        ),
        playbackSpeed = stored.playbackSpeed,
        completed = stored.completed,
        lastPlayedAtMilliseconds = stored.lastPlayedAtMilliseconds,
        lastUpdatedAtMilliseconds = stored.lastUpdatedAtMilliseconds,
        fileSizeBytes = fileSizeBytes,
    )

    private fun querySize(uri: Uri): Long = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)).coerceAtLeast(0)
            } else {
                0L
            }
        } ?: 0L
    }.getOrDefault(0L)

    /**
     * Feeds [GenreStore] for the Listening Stats screen. Guarded by [GenreStore.hasAttempted]
     * so a rescan (which happens often -- every manual refresh, every app open) doesn't
     * re-tag and re-persist the same book on every pass; a locally-edited file whose tag
     * genuinely changes just won't be picked up until [GenreStore] is cleared, which is an
     * acceptable tradeoff for how rarely that happens versus how often scans run.
     */
    private fun recordGenreIfNeeded(stableId: String, rawGenre: String) {
        if (GenreStore.hasAttempted(stableId)) return
        val genres = rawGenre.split('/', ';', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        GenreStore.record(stableId, genres)
    }

    private fun readMetadata(uri: Uri): EmbeddedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // Best-effort genre for the Listening Stats screen -- an ID3/M4B "genre"
            // frame, when the file actually has one tagged. Many audiobook rips don't,
            // in which case this is blank and the book just shows as "Unknown" there;
            // that's expected, not an error.
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty().trim()
            Log.d(TAG, "metadata available=${title.isNotBlank() || author.isNotBlank()}")
            EmbeddedMetadata(title, author, duration, genre)
        } catch (_: Exception) {
            EmbeddedMetadata()
        } finally {
            retriever.release()
        }
    }

    private fun String.removeSupportedSuffix(): String = when {
        endsWith(".mp3", ignoreCase = true) -> dropLast(4)
        endsWith(".m4b", ignoreCase = true) -> dropLast(4)
        else -> this
    }

    private fun String.hasSupportedExtension(): Boolean =
        endsWith(".mp3", ignoreCase = true) ||
            endsWith(".m4b", ignoreCase = true) ||
            endsWith(".m4a", ignoreCase = true)

    private data class EmbeddedMetadata(
        val title: String = "",
        val author: String = "",
        val durationMilliseconds: Long = 0,
        val genre: String = "",
    )
}
