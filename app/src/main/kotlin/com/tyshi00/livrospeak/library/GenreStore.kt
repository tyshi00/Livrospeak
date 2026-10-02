package com.tyshi00.livrospeak.library

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private const val GENRE_FILE_NAME = "genre_cache.json"

/**
 * Best-effort genre/subject lookup, shared across all three book sources and keyed by
 * each book's own stable id (Libby loan URLs, "external:<mediaId>" for local files, the
 * sha256-based ids RssFeedRepository already mints for feed items -- these never
 * collide across sources, so one flat map is enough).
 *
 * Written once per book and never refreshed after that: genre metadata is treated as
 * permanent for a given title. This matters most for Libby, where each lookup costs a
 * background navigation of the shared hidden WebView (see LibbyWebPlayer.fetchLoanGenres)
 * -- a cache-forever policy means that cost is paid at most once per book, ever, no
 * matter how many times the Listening Stats screen is opened.
 *
 * A missing entry means "never looked up" OR "looked up and genuinely found nothing" --
 * [hasAttempted] is what distinguishes those two for callers that need to avoid retrying
 * a lookup forever (the Libby idle-fetch loop). Display code should just treat a missing
 * entry as "Unknown" without needing that distinction.
 */
object GenreStore {
    private lateinit var appContext: Context
    private val cache = linkedMapOf<String, List<String>>()
    private val attempted = mutableSetOf<String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        loadFromDisk()
    }

    @Synchronized
    fun genresFor(bookId: String): List<String> = cache[bookId].orEmpty()

    @Synchronized
    fun hasAttempted(bookId: String): Boolean = bookId in attempted

    /**
     * Records the result of a genre lookup for [bookId]. Safe to call repeatedly with
     * the same id (e.g. a local-file rescan, or an RSS feed refresh) -- later calls
     * simply overwrite earlier ones, so a book whose tags change (rare, but possible
     * for a locally-edited file) stays current rather than being stuck on its first
     * answer forever. [genres] may be empty, meaning "looked up, genuinely has none" --
     * that still marks the id as attempted so it isn't retried forever.
     */
    @Synchronized
    fun record(bookId: String, genres: List<String>) {
        attempted += bookId
        val cleaned = genres.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) {
            cache.remove(bookId)
        } else {
            cache[bookId] = cleaned
        }
        writeToDisk()
    }

    @Synchronized
    fun all(): Map<String, List<String>> = cache.toMap()

    private fun loadFromDisk() {
        val file = File(appContext.filesDir, GENRE_FILE_NAME)
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            json.optJSONObject("genres")?.let { genresJson ->
                genresJson.keys().forEach { key ->
                    val array = genresJson.optJSONArray(key) ?: return@forEach
                    cache[key] = (0 until array.length()).map { array.getString(it) }
                }
            }
            json.optJSONArray("attempted")?.let { array ->
                for (index in 0 until array.length()) attempted += array.getString(index)
            }
        }
    }

    private fun writeToDisk() {
        val genresJson = JSONObject()
        cache.forEach { (id, genres) -> genresJson.put(id, JSONArray(genres)) }
        val json = JSONObject()
            .put("genres", genresJson)
            .put("attempted", JSONArray(attempted.toList()))
        runCatching {
            File(appContext.filesDir, GENRE_FILE_NAME).writeText(json.toString())
        }
    }
}
