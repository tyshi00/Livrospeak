package com.tyshi00.livrospeak.library

import android.content.Context
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val LIBRARIES_FILE_NAME = "libby_libraries.json"

/**
 * One library Livrospeak searches in Libby.
 *
 * [key] is the library's name in Libby's own addresses: the `cobbcounty` in
 * `libbyapp.com/library/cobbcounty`. Libby resolves that name by itself when a search
 * address is opened (confirmed on a real device: `search/cobbcounty/search/query-dune/
 * page-1` went through Libby's `interview/locate-library/resolve?key=cobbcounty` and
 * landed on Search Results), so the key alone is enough to search any library.
 *
 * [addedByHand] is true for a library typed in through Settings → Libby → Add Library,
 * and false for one read from the Libby account's Menu screen.
 */
data class LibbyLibrary(
    val key: String,
    val name: String = "",
    val addedByHand: Boolean = false,
) {
    /** Search results remember the library they came from by this. */
    val id: String get() = key

    val displayName: String get() = name.ifBlank { key }

    /**
     * Libby's search address for this library. With [audiobooksOnly], Libby is asked for
     * audiobooks only (the `audiobooks/` part, from a real captured search address), so
     * a page is not filled with ebooks the result scraper would skip anyway.
     */
    fun searchUrl(query: String, page: Int, audiobooksOnly: Boolean = true): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val format = if (audiobooksOnly) "audiobooks/" else ""
        return "https://libbyapp.com/search/${encodedKey()}/search/${format}query-$encodedQuery/page-$page"
    }

    /** The library's own page in Libby, where its name is shown. */
    fun libraryPageUrl(): String = "https://libbyapp.com/library/${encodedKey()}"

    private fun encodedKey(): String = URLEncoder.encode(key, "UTF-8").replace("+", "%20")

    companion object {
        private val KEY_CHARACTERS = Regex("^[a-z0-9][a-z0-9_-]*$")
        private val LIBRARY_PATH = Regex("^/library/([^/?#]+)")
        private val KEY_IN_ADDRESS = Regex("libbyapp\\.com/(?:library|search)/([^/?#\\s]+)")

        /**
         * The library name in a Libby page path such as `/library/cobbcounty`, which is
         * where Libby goes when a library is tapped on its Menu screen. Null for any
         * other path.
         */
        fun keyFromLibraryPath(path: String): String? {
            val raw = LIBRARY_PATH.find(path.trim())?.groupValues?.get(1) ?: return null
            return cleanKey(raw)
        }

        /**
         * What someone typed into Add Library, as a library name: either the name itself
         * (`cobbcounty`) or a pasted Libby address that contains it
         * (`libbyapp.com/library/cobbcounty`). Null if it can't be a Libby library name.
         */
        fun keyFromTypedText(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val raw = KEY_IN_ADDRESS.find(trimmed)?.groupValues?.get(1) ?: trimmed
            return cleanKey(raw)
        }

        private fun cleanKey(raw: String): String? {
            val decoded = try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: IllegalArgumentException) {
                raw
            }
            val key = decoded.trim().lowercase()
            return key.takeIf { KEY_CHARACTERS.matches(it) }
        }
    }
}

/**
 * The Libby libraries Livrospeak searches, saved on the phone so they are found once
 * rather than before every search.
 *
 * Libraries read from the Libby account ([replaceFound]) and libraries added by hand
 * ([addByHand]) are kept together. Reading the account again replaces only what was
 * read before, so libraries added by hand survive a refresh. Everything is cleared when
 * Libby is disconnected ([clear]), so the next account starts fresh.
 *
 * Only library names are kept here. Library card numbers are never saved (see
 * LibraryCardPinStore).
 */
object LibbyLibraryStore {
    private var appContext: Context? = null
    private val mutableLibraries = MutableStateFlow<List<LibbyLibrary>>(emptyList())
    val libraries: StateFlow<List<LibbyLibrary>> = mutableLibraries.asStateFlow()

    val current: List<LibbyLibrary>
        get() = mutableLibraries.value

    fun init(context: Context) {
        appContext = context.applicationContext
        mutableLibraries.value = readFromDisk()
    }

    /**
     * Replaces the libraries previously read from the Libby account with [found],
     * keeping every library added by hand that isn't in [found]. A library added by
     * hand that the account also has becomes a found one (with the account's name).
     */
    fun replaceFound(found: List<LibbyLibrary>) {
        val foundByKey = LinkedHashMap<String, LibbyLibrary>()
        found.forEach { library ->
            if (library.key.isNotBlank() && library.key !in foundByKey) {
                foundByKey[library.key] = library.copy(addedByHand = false)
            }
        }
        val keptByHand = mutableLibraries.value.filter { it.addedByHand && it.key !in foundByKey }
        save(foundByKey.values.toList() + keptByHand)
    }

    /**
     * Adds a library by its Libby name, with the [name] Libby shows for it. Returns
     * false if [key] isn't a usable library name. A library the account already has
     * is left as it is; one added by hand before gets the new [name] (libraries added
     * before names were looked up had none).
     */
    fun addByHand(key: String, name: String = ""): Boolean {
        val cleaned = LibbyLibrary.keyFromTypedText(key) ?: return false
        val existing = mutableLibraries.value.firstOrNull { it.key == cleaned }
        when {
            existing == null ->
                save(mutableLibraries.value + LibbyLibrary(key = cleaned, name = name, addedByHand = true))
            existing.addedByHand && name.isNotBlank() && existing.name != name ->
                save(mutableLibraries.value.map { if (it.key == cleaned) it.copy(name = name) else it })
        }
        return true
    }

    /** Removes a library added by hand. Libraries read from the account are left alone. */
    fun removeByHand(key: String) {
        val remaining = mutableLibraries.value.filterNot { it.addedByHand && it.key == key }
        if (remaining.size != mutableLibraries.value.size) save(remaining)
    }

    fun clear() {
        save(emptyList())
    }

    private fun save(libraries: List<LibbyLibrary>) {
        if (libraries == mutableLibraries.value) return
        mutableLibraries.value = libraries
        writeToDisk(libraries)
    }

    private fun file(): File? = appContext?.let { File(it.filesDir, LIBRARIES_FILE_NAME) }

    private fun readFromDisk(): List<LibbyLibrary> {
        val file = file() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONObject(file.readText()).optJSONArray("libraries") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val key = LibbyLibrary.keyFromTypedText(item.optString("key")) ?: return@mapNotNull null
                LibbyLibrary(
                    key = key,
                    name = item.optString("name"),
                    addedByHand = item.optBoolean("addedByHand", false),
                )
            }.distinctBy { it.key }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeToDisk(libraries: List<LibbyLibrary>) {
        val file = file() ?: return
        val array = JSONArray()
        libraries.forEach { library ->
            array.put(
                JSONObject()
                    .put("key", library.key)
                    .put("name", library.name)
                    .put("addedByHand", library.addedByHand),
            )
        }
        try {
            file.writeText(JSONObject().put("libraries", array).toString())
        } catch (_: Exception) {
            // The list still works for this run; it is read from Libby again next time.
        }
    }
}
