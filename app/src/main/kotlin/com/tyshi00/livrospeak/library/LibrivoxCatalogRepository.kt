package com.tyshi00.livrospeak.library

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "LibrivoxCatalog"
private const val API_BASE = "https://librivox.org/api/feed/audiobooks/"
private const val REQUESTED_FIELDS = "id,title,authors,totaltime,totaltimesecs,url_zip_file,language,num_sections"
private val LEADING_ARTICLE = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

data class LibrivoxSearchResult(
    val id: String,
    val title: String,
    val author: String,
    val totalTimeText: String,
    val totalTimeSeconds: Long,
    val zipUrl: String,
    val language: String,
    val sectionCount: Int,
)

sealed interface LibrivoxSearchOutcome {
    data class Success(val results: List<LibrivoxSearchResult>) : LibrivoxSearchOutcome
    data object Empty : LibrivoxSearchOutcome
    // Carries the actual failure reason (an HTTP status, or an exception's own
    // class+message) so the screen can show it, not just logcat.
    data class NetworkError(val detail: String) : LibrivoxSearchOutcome
}

/**
 * Read-only client for LibriVox's public catalog API (https://librivox.org/api/info).
 * No account, no auth, no local state -- every call is a fresh request.
 *
 * Only catalog entries with a non-blank `url_zip_file` are returned: that field is
 * the archive.org-hosted zip of every finished audio file for the book, and is the
 * thing [LibrivoxDownloadManager] actually downloads. A handful of in-progress
 * LibriVox projects don't have one yet -- those are filtered out here rather than
 * shown as a result that can't actually be downloaded.
 */
object LibrivoxCatalogRepository {
    /**
     * Searches by title, then by author if no title matches.
     *
     * The request shape is the query-parameter form
     * (`.../audiobooks/?title=^term&format=json`). That is the only form with a
     * confirmed real result from a device (a "romeo and juliet" search returned
     * only Romeo and Juliet recordings). The path form (`.../audiobooks/title/^term`)
     * was tried afterwards and every search came back HTTP 403, so it was reverted.
     *
     * LibriVox answers HTTP 404 when nothing matches, and `^` anchors the term to
     * the start of the title. "the tell-tale heart" returned 404 while "romeo and
     * juliet" matched, which fits the catalog storing titles without their leading
     * article. That is an inference from those two results, not something read
     * from LibriVox itself, so the attempts below go from the known-good form to
     * progressively looser ones and stop at the first that returns anything:
     *   1. title starting with the term as typed
     *   2. title starting with the term minus a leading the/a/an
     *   3. title containing the term anywhere
     *   4. author matching the term's last word (a surname)
     */
    suspend fun search(query: String, limit: Int = 25): LibrivoxSearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return LibrivoxSearchOutcome.Empty
        val withoutArticle = trimmed.replaceFirst(LEADING_ARTICLE, "").trim()
        val titleAttempts = buildList {
            add("^$trimmed")
            if (withoutArticle.isNotBlank() && withoutArticle != trimmed) add("^$withoutArticle")
            add(withoutArticle.ifBlank { trimmed })
        }
        // HttpURLConnection is blocking I/O, and this is called from a Main-dispatcher
        // coroutine scope (LibrivoxScreen's rememberCoroutineScope), where Android
        // throws NetworkOnMainThreadException. Every request below runs on IO.
        return withContext(Dispatchers.IO) {
            for (term in titleAttempts) {
                val outcome = fetch("title", term, limit)
                // Anything other than a clean "no match" ends the search: results
                // are returned, and a real failure (no connection, an HTTP error)
                // is reported as-is instead of being retried three more times.
                if (outcome !is LibrivoxSearchOutcome.Empty) return@withContext outcome
            }
            // No title matched. The term may be an author's name ("edgar allan poe"
            // was a real search). Best-effort only: the title attempts above already
            // got clean answers from LibriVox, so if this extra lookup fails the
            // truthful result for the search is still "no results", not an error.
            val surname = trimmed.substringAfterLast(' ')
            fetch("author", surname, limit) as? LibrivoxSearchOutcome.Success
                ?: LibrivoxSearchOutcome.Empty
        }
    }

    /**
     * One request to the audiobooks feed, filtered by [parameter] ("title" or
     * "author"). A leading `^` in [term] is LibriVox's own start-of-field anchor
     * and is sent as-is; only the text after it is URL-encoded.
     */
    private fun fetch(parameter: String, term: String, limit: Int): LibrivoxSearchOutcome {
        val anchor = if (term.startsWith("^")) "^" else ""
        val encodedTerm = URLEncoder.encode(term.removePrefix("^"), "UTF-8")
        val requestUrl = "$API_BASE?$parameter=$anchor$encodedTerm" +
            "&format=json" +
            "&limit=${limit.coerceIn(1, 100)}" +
            "&fields=$REQUESTED_FIELDS"

        return runCatching {
            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Livrospeak/Android")
            }
            val body = try {
                val code = connection.responseCode
                if (code == 404) {
                    // LibriVox's answer for "nothing matched", an ordinary outcome
                    // of a search rather than a failure to reach it.
                    Log.d(TAG, "fetch($parameter): HTTP 404, no match")
                    return LibrivoxSearchOutcome.Empty
                }
                if (code !in 200..299) {
                    val detail = "HTTP $code"
                    Log.w(TAG, "fetch($parameter) failed: $detail from librivox.org")
                    return LibrivoxSearchOutcome.NetworkError(detail)
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            parseResults(body)
        }.getOrElse { error ->
            // Returned to the caller so a real failure (DNS, TLS handshake, timeout,
            // connection reset) shows up on screen as what it actually was.
            val detail = "${error.javaClass.simpleName}: ${error.message}"
            Log.w(TAG, "fetch($parameter) failed: $detail")
            LibrivoxSearchOutcome.NetworkError(detail)
        }
    }

    private fun parseResults(body: String): LibrivoxSearchOutcome {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: run {
            Log.w(TAG, "search failed: response body wasn't valid JSON")
            return LibrivoxSearchOutcome.NetworkError("response wasn't valid JSON")
        }
        val books = root.optJSONArray("books") ?: return LibrivoxSearchOutcome.Empty
        val results = buildList {
            for (index in 0 until books.length()) {
                val entry = books.optJSONObject(index) ?: continue
                val zipUrl = entry.optString("url_zip_file").trim()
                if (zipUrl.isBlank()) continue
                val id = entry.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    LibrivoxSearchResult(
                        id = id,
                        title = entry.optString("title").trim().ifBlank { "Untitled" },
                        author = firstAuthorName(entry),
                        totalTimeText = entry.optString("totaltime").trim(),
                        totalTimeSeconds = entry.opt("totaltimesecs")?.toString()?.toLongOrNull() ?: 0L,
                        zipUrl = zipUrl,
                        language = entry.optString("language").trim(),
                        sectionCount = entry.opt("num_sections")?.toString()?.toIntOrNull() ?: 0,
                    ),
                )
            }
        }
        return if (results.isEmpty()) LibrivoxSearchOutcome.Empty else LibrivoxSearchOutcome.Success(results)
    }

    private fun firstAuthorName(entry: JSONObject): String {
        val authors = entry.optJSONArray("authors") ?: return ""
        if (authors.length() == 0) return ""
        val first = authors.optJSONObject(0) ?: return ""
        val name = listOf(first.optString("first_name").trim(), first.optString("last_name").trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return if (authors.length() > 1 && name.isNotBlank()) "$name et al." else name
    }
}
