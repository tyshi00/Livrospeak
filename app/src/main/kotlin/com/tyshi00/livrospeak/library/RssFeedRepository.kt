package com.tyshi00.livrospeak.library

import android.content.Context
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val PREFERENCES_NAME = "rss_feeds"
private const val FEEDS_KEY = "feeds"
private const val MAX_FEED_BYTES = 5 * 1024 * 1024
private const val MAX_REDIRECTS = 5

data class RssFeed(
    val id: String,
    val url: String,
    val title: String,
    val books: List<Audiobook>,
)

sealed interface RssFeedResult {
    data class Success(val feed: RssFeed) : RssFeedResult
    data class Error(val userMessage: String) : RssFeedResult
}

/** App-private RSS configuration, cached metadata, fetching, and secure XML parsing. */
object RssFeedRepository {
    private lateinit var appContext: Context
    private val mutex = Mutex()
    private val mutableFeeds = MutableStateFlow<List<RssFeed>>(emptyList())
    private val mutableReady = MutableStateFlow(false)
    private val mutableRefreshingIds = MutableStateFlow<Set<String>>(emptySet())

    val feeds: StateFlow<List<RssFeed>> = mutableFeeds.asStateFlow()
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()
    val refreshingIds: StateFlow<Set<String>> = mutableRefreshingIds.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        mutableFeeds.value = readCachedFeeds()
        mutableReady.value = true
    }

    fun validateUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.US) !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        return trimmed
    }

    fun displayName(feed: RssFeed): String = feed.title.ifBlank {
        runCatching { Uri.parse(feed.url).host.orEmpty() }.getOrDefault("")
            .ifBlank { "RSS Feed" }
    }

    suspend fun addFeed(rawUrl: String): RssFeedResult = mutex.withLock {
        val url = validateUrl(rawUrl)
            ?: return RssFeedResult.Error("Enter a valid http or https URL.")
        if (mutableFeeds.value.any { it.url == url }) {
            return RssFeedResult.Error("Feed already added.")
        }
        val result = fetchFeed(url)
        if (result is RssFeedResult.Success) {
            mutableFeeds.value = (mutableFeeds.value + result.feed)
                .sortedBy { displayName(it).lowercase(Locale.US) }
            persist()
        }
        result
    }

    suspend fun refreshFeed(feedId: String): RssFeedResult = mutex.withLock {
        val existing = mutableFeeds.value.firstOrNull { it.id == feedId }
            ?: return RssFeedResult.Error("Feed unavailable.")
        mutableRefreshingIds.value = mutableRefreshingIds.value + feedId
        try {
            val result = fetchFeed(existing.url)
            if (result is RssFeedResult.Success) {
                mutableFeeds.value = mutableFeeds.value.map {
                    if (it.id == feedId) result.feed else it
                }
                persist()
            }
            result
        } finally {
            mutableRefreshingIds.value = mutableRefreshingIds.value - feedId
        }
    }

    suspend fun refreshAll() {
        mutableFeeds.value.map { it.id }.forEach { refreshFeed(it) }
    }

    suspend fun removeFeed(feedId: String) = mutex.withLock {
        mutableFeeds.value = mutableFeeds.value.filterNot { it.id == feedId }
        persist()
    }

    private suspend fun fetchFeed(feedUrl: String): RssFeedResult = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = download(feedUrl)
            parseFeed(feedUrl, bytes)
        }.getOrElse {
            RssFeedResult.Error("Could not load feed.")
        }
    }

    private fun download(initialUrl: String): ByteArray {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val validated = validateUrl(current) ?: error("Unsupported redirect")
            val connection = (URL(validated).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
                setRequestProperty("User-Agent", "Livrospeak/Android")
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    if (redirectCount == MAX_REDIRECTS) error("Too many redirects")
                    current = URL(URL(validated), connection.getHeaderField("Location") ?: error("Missing redirect")).toString()
                    return@repeat
                }
                if (status !in 200..299) error("HTTP error")
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_FEED_BYTES) error("Feed too large")
                        output.write(buffer, 0, count)
                    }
                    return output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Redirect failed")
    }

    private fun parseFeed(feedUrl: String, bytes: ByteArray): RssFeedResult {
        if (bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) {
            return RssFeedResult.Error("Could not load feed.")
        }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            setInput(ByteArrayInputStream(bytes), null)
        }
        var feedTitle = ""
        var insideItem = false
        var currentTag = ""
        var item = ParsedItem()
        val parsedItems = mutableListOf<ParsedItem>()
        // Channel-level <category>/<itunes:category> tags (outside any <item>) are a
        // best-effort genre fallback for items that don't carry their own -- common for
        // single-genre podcast/audiobook feeds that only declare it once at the top.
        val feedCategories = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.lowercase(Locale.US)
                    if (currentTag == "item") {
                        insideItem = true
                        item = ParsedItem()
                    } else if (insideItem && currentTag == "enclosure" && item.enclosureUrl.isBlank()) {
                        item = item.copy(
                            enclosureUrl = parser.getAttributeValue(null, "url").orEmpty().trim(),
                            enclosureType = parser.getAttributeValue(null, "type").orEmpty().trim(),
                        )
                    } else if (currentTag == "category") {
                        // itunes:category is almost always self-closing with a "text"
                        // attribute (<itunes:category text="Fiction"/>) rather than a
                        // text node, unlike plain RSS <category>Fiction</category> --
                        // grab the attribute here immediately; the TEXT branch below
                        // covers the plain-RSS shape.
                        val attributeValue = parser.getAttributeValue(null, "text").orEmpty().trim()
                        if (attributeValue.isNotBlank()) {
                            if (insideItem) {
                                item = item.copy(categories = item.categories + attributeValue)
                            } else {
                                feedCategories += attributeValue
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.orEmpty().trim()
                    if (text.isNotBlank()) {
                        if (insideItem) {
                            item = when (currentTag) {
                                "title" -> item.copy(title = appendText(item.title, text))
                                "guid" -> item.copy(guid = appendText(item.guid, text))
                                "author", "creator" -> item.copy(author = appendText(item.author, text))
                                "pubdate", "published" -> item.copy(publicationDate = appendText(item.publicationDate, text))
                                "duration" -> item.copy(durationText = appendText(item.durationText, text))
                                "category" -> item.copy(categories = item.categories + text)
                                else -> item
                            }
                        } else if (currentTag == "title" && feedTitle.isBlank()) {
                            feedTitle = text
                        } else if (currentTag == "category") {
                            feedCategories += text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true)) {
                        parsedItems += item
                        insideItem = false
                    }
                    currentTag = ""
                }
            }
            parser.next()
        }

        val feedId = sha256(feedUrl)
        val books = parsedItems.mapNotNull { parsed ->
            if (!isSupportedEnclosure(parsed.enclosureUrl, parsed.enclosureType)) return@mapNotNull null
            val identity = when {
                parsed.guid.isNotBlank() -> "guid:${parsed.guid}"
                parsed.enclosureUrl.isNotBlank() -> "enclosure:${normalizeUrl(parsed.enclosureUrl)}"
                else -> "fallback:${parsed.title}:${parsed.publicationDate}"
            }
            val id = sha256("$feedUrl\n$identity")
            if (!GenreStore.hasAttempted(id)) {
                GenreStore.record(id, parsed.categories.ifEmpty { feedCategories })
            }
            val stored = AudiobookProgressStore.read(AudiobookSource.Rss, id)
            Audiobook(
                id = id,
                source = AudiobookSource.Rss,
                title = parsed.title.ifBlank { "Untitled audiobook" },
                author = parsed.author,
                playbackReference = parsed.enclosureUrl,
                durationMilliseconds = parseDurationMilliseconds(parsed.durationText)
                    .takeIf { it > 0 } ?: stored.durationMilliseconds,
                positionMilliseconds = stored.positionMilliseconds,
                playbackSpeed = stored.playbackSpeed,
                completed = stored.completed,
                lastPlayedAtMilliseconds = stored.lastPlayedAtMilliseconds,
                lastUpdatedAtMilliseconds = stored.lastUpdatedAtMilliseconds,
            )
        }
        if (books.isEmpty()) return RssFeedResult.Error("No supported audiobook files found.")
        return RssFeedResult.Success(
            RssFeed(
                id = feedId,
                url = feedUrl,
                title = feedTitle,
                books = books.sortedBy { it.title.lowercase(Locale.US) },
            ),
        )
    }

    private fun persist() {
        val feedsJson = JSONArray()
        mutableFeeds.value.forEach { feed ->
            val booksJson = JSONArray()
            feed.books.forEach { book ->
                booksJson.put(
                    JSONObject()
                        .put("id", book.id)
                        .put("title", book.title)
                        .put("author", book.author)
                        .put("reference", book.playbackReference)
                        .put("duration", book.durationMilliseconds),
                )
            }
            feedsJson.put(
                JSONObject()
                    .put("id", feed.id)
                    .put("url", feed.url)
                    .put("title", feed.title)
                    .put("books", booksJson),
            )
        }
        preferences().edit().putString(FEEDS_KEY, feedsJson.toString()).commit()
    }

    private fun readCachedFeeds(): List<RssFeed> = runCatching {
        val feedsJson = JSONArray(preferences().getString(FEEDS_KEY, "[]"))
        buildList {
            repeat(feedsJson.length()) { feedIndex ->
                val feedJson = feedsJson.getJSONObject(feedIndex)
                val booksJson = feedJson.optJSONArray("books") ?: JSONArray()
                val books = buildList {
                    repeat(booksJson.length()) { bookIndex ->
                        val bookJson = booksJson.getJSONObject(bookIndex)
                        val id = bookJson.getString("id")
                        val stored = AudiobookProgressStore.read(AudiobookSource.Rss, id)
                        add(
                            Audiobook(
                                id = id,
                                source = AudiobookSource.Rss,
                                title = bookJson.optString("title", "Untitled audiobook"),
                                author = bookJson.optString("author"),
                                playbackReference = bookJson.optString("reference"),
                                durationMilliseconds = stored.durationMilliseconds.takeIf { it > 0 }
                                    ?: bookJson.optLong("duration"),
                                positionMilliseconds = stored.positionMilliseconds,
                                playbackSpeed = stored.playbackSpeed,
                                completed = stored.completed,
                                lastPlayedAtMilliseconds = stored.lastPlayedAtMilliseconds,
                                lastUpdatedAtMilliseconds = stored.lastUpdatedAtMilliseconds,
                            ),
                        )
                    }
                }
                add(
                    RssFeed(
                        id = feedJson.getString("id"),
                        url = feedJson.getString("url"),
                        title = feedJson.optString("title"),
                        books = books,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun preferences() =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

private data class ParsedItem(
    val guid: String = "",
    val title: String = "",
    val author: String = "",
    val publicationDate: String = "",
    val durationText: String = "",
    val enclosureUrl: String = "",
    val enclosureType: String = "",
    val categories: List<String> = emptyList(),
)

private fun appendText(existing: String, next: String): String =
    if (existing.isBlank()) next else "$existing $next"

private fun isSupportedEnclosure(url: String, mimeType: String): Boolean {
    if (validateHttpUrl(url) == null) return false
    val type = mimeType.substringBefore(';').trim().lowercase(Locale.US)
    if (type in setOf("audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4b", "audio/m4b")) return true
    val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
    return path.endsWith(".mp3", ignoreCase = true) || path.endsWith(".m4b", ignoreCase = true)
}

private fun validateHttpUrl(url: String): String? {
    val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return null
    return url.takeIf {
        uri.scheme?.lowercase(Locale.US) in setOf("http", "https") && !uri.host.isNullOrBlank()
    }
}

private fun normalizeUrl(url: String): String = runCatching {
    val uri = Uri.parse(url.trim())
    uri.buildUpon().fragment(null).build().toString()
}.getOrDefault(url.trim())

private fun parseDurationMilliseconds(value: String): Long {
    if (value.isBlank()) return 0
    val parts = value.trim().split(':').mapNotNull(String::toLongOrNull)
    val seconds = when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> 0
    }
    return seconds.coerceAtLeast(0) * 1000
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
