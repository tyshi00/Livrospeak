package com.tyshi00.livrospeak

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import org.json.JSONArray
import org.json.JSONObject
import com.tyshi00.livrospeak.library.HoldItem
import com.tyshi00.livrospeak.library.LibbyAvailability
import com.tyshi00.livrospeak.library.LibbySearchResult
import com.tyshi00.livrospeak.library.LibbyTag
import com.tyshi00.livrospeak.library.LibraryCard
import com.tyshi00.livrospeak.library.LoanItem
import com.tyshi00.livrospeak.library.TaggedTitle
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "LibbyWebPlayer"
private const val LIBBY_ROOT = "https://libbyapp.com/"
private const val LIBBY_SHELF = "https://libbyapp.com/shelf"
private const val LIBBY_SHELF_HOLDS = "https://libbyapp.com/shelf/holds"
// A "/shelf/tags" constant lived here before -- removed after a real device capture
// confirmed it isn't a real route (Libby's own router logged "Unused segment: tags"
// and stayed on plain Shelf). The real route, confirmed via a later capture (address
// bar + router log both landing on it directly), is plain "/tags" -- see LIBBY_TAGS
// and the Tags section below.
private const val LIBBY_TAGS = "https://libbyapp.com/tags"
// UNCONFIRMED against a real device capture (same situation Holds/Renew initially
// shipped in) -- Libby's own help docs describe Library Cards as reached from the
// account menu rather than from the Shelf, so this route is a best-effort guess
// following the same "/shelf/{feature}" shape /shelf/holds confirmed, not something
// read off a real address bar. If it turns out wrong, loadLibraryCardsPage() will
// land somewhere that doesn't scrape any cards, and scrapeLibraryCards() below logs
// a small page sample in that case specifically so the fix is a one-line selector
// change rather than another manual capture.
// Confirmed via a real captured screenshot of the account menu's own address bar:
// the Menu screen (where "Cards" lives, one per connected library) is a hash route
// under /interview/menu, not a /shelf/* path the way Holds/Tags are. Unlike those,
// this one route shows every connected library at once rather than one card list
// -- see scrapeLibraryCards's doc comment for how each library's own card number
// is read from here.
private const val LIBBY_MENU = "https://libbyapp.com/interview/menu#main-menu"

/**
 * Application-scoped Libby browser session.
 *
 * Livrospeak keeps this WebView mounted behind its native Light-style UI so Libby's
 * session and playback remain persistent without exposing Libby's interface.
 */
object LibbyWebPlayer {

    /**
     * One Libby-linked library card on this account, and how to build a search URL
     * for it. Libby's search-route shape is NOT uniform across every library:
     *
     * - Cobb County's confirmed real URL: `.../search/cobbcounty/search/audiobooks/
     *   query-.../page-1/12073324` -- slug, an "audiobooks" format segment, AND a
     *   trailing numeric website id.
     * - Sequoyah Regional's confirmed real URL: `.../search/sequoyah/search/
     *   query-.../page-1` -- just the slug, no format segment, no website id at all.
     *
     * So rather than assume one template fits every library (which was the
     * original, Cobb-only bug: a card with a differently-shaped route would either
     * 404 or silently mis-resolve), each entry here carries its OWN known-working
     * shape. [includeAudiobooksSegment] and [websiteId] both default to "leave it
     * out", matching Sequoyah's simpler/more common shape -- the "audiobooks"
     * segment isn't actually required for correctness even when present, since
     * [scrapeSearchResults] already filters tiles by their own `format_audiobook`
     * class regardless of what the URL asked for; it's Cobb's config that opts
     * into the extra segments because that's the shape its own captured URL
     * needed.
     *
     * To add another linked card later: search that library in the real Libby app
     * or website, copy the resulting URL (same way these two were captured), and
     * add an entry to [LIBBY_LIBRARIES] matching its shape.
     */
    data class LibbyLibrary(
        val id: String,
        val displayName: String,
        val slug: String,
        val websiteId: String? = null,
        val includeAudiobooksSegment: Boolean = false,
    ) {
        fun searchUrl(query: String, page: Int): String {
            val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val formatSegment = if (includeAudiobooksSegment) "audiobooks/" else ""
            val base = "https://libbyapp.com/search/$slug/search/$formatSegment" +
                "query-$encoded/page-$page"
            return if (websiteId != null) "$base/$websiteId" else base
        }
    }

    /**
     * Every library card Livrospeak currently knows how to search. Searching
     * queries ALL of these for every search (each roughly doubles -- triples,
     * etc. -- how long a search takes, since each is its own page-by-page
     * scrape), so this list is meant to stay short: it's not a general "add your
     * own library" feature, just the known cards for this account. See the Libby
     * screen in Settings for the same list shown to the user.
     */
    val LIBBY_LIBRARIES = listOf(
        LibbyLibrary(
            id = "cobbcounty",
            displayName = "Cobb County Public Library",
            slug = "cobbcounty",
            websiteId = "12073324",
            includeAudiobooksSegment = true,
        ),
        LibbyLibrary(
            id = "sequoyah",
            displayName = "Sequoyah Regional Library",
            slug = "sequoyah",
        ),
    )

    private var webView: WebView? = null
    private var applicationContext: Context? = null
    private val mutableRendererFailed = MutableStateFlow(false)
    val rendererFailed: StateFlow<Boolean> = mutableRendererFailed.asStateFlow()

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
        if (webView != null) return
        applicationContext = context.applicationContext

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        val contextWrapper = MutableContextWrapper(context.applicationContext)
        val isDebugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(isDebugBuild)

        webView = WebView(contextWrapper).apply {
            settings.javaScriptEnabled = true
            // This WebView is a hidden automation driver, never a user-facing browser
            // tab, so there's no benefit to letting it serve a cached page -- only
            // risk. Suspected root cause of a real report: returning a loan, then
            // immediately re-searching for it and having the search tile still show
            // the OLD "Open Audiobook" state instead of a fresh Borrow/Place-Hold
            // button, even though the real libbyapp.com site reflected the return
            // right away. LOAD_NO_CACHE forces every loadUrl/navigate call in this
            // file to go to the network instead of risking a stale cached response.
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true

            cookies.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    mutableRendererFailed.value = false
                    Log.d(TAG, "onPageFinished: ${safeRouteName(url)}")
                    CookieManager.getInstance().flush()
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    Log.e(TAG, "Libby renderer disconnected crashed=${detail.didCrash()}")
                    mutableRendererFailed.value = true
                    (view.parent as? ViewGroup)?.removeView(view)
                    webView = null
                    LibbyBridge.resetForRecreatedWebView()
                    view.destroy()
                    Handler(Looper.getMainLooper()).post {
                        applicationContext?.let(::init)
                        loadLoginPage()
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    // Do not forward Libby's console text or source URLs to Logcat; they may
                    // contain authentication or setup details.
                    return true
                }
            }

            // Install the cross-origin player-frame bridge before any Libby URL loads.
            LibbyBridge.install(this)
        }
    }

    fun attachToActivity(activityContext: Context): WebView {
        val current = requireWebView()
        (current.context as? MutableContextWrapper)?.baseContext = activityContext
        current.requestFocus()
        return current
    }

    fun requireWebView(): WebView =
        webView ?: error("LibbyWebPlayer.init(context) must be called before use")

    fun detachFromCurrentParent() {
        val current = webView ?: return
        (current.parent as? ViewGroup)?.removeView(current)
        applicationContext?.let { appContext ->
            (current.context as? MutableContextWrapper)?.baseContext = appContext
        }
    }

    fun loadLoginPage() {
        requireWebView().loadUrl(LIBBY_ROOT)
    }

    /** Clears only this app's local WebView-backed Libby session. */
    fun clearLocalLibbySession(onComplete: () -> Unit) {
        LibbyBridge.stopPlaybackHost()
        val current = requireWebView()
        val clearPageStorage = """
            (() => {
              try { localStorage.clear(); } catch (_) {}
              try { sessionStorage.clear(); } catch (_) {}
              try {
                if (window.caches) {
                  caches.keys().then(keys => keys.forEach(key => caches.delete(key)));
                }
              } catch (_) {}
              try {
                if (indexedDB.databases) {
                  indexedDB.databases().then(databases => databases.forEach(database => {
                    if (database.name) indexedDB.deleteDatabase(database.name);
                  }));
                }
              } catch (_) {}
              return true;
            })();
        """.trimIndent()

        current.evaluateJavascript(clearPageStorage) {
            WebStorage.getInstance().deleteAllData()
            current.clearCache(true)
            current.clearHistory()
            current.clearFormData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                current.loadUrl(LIBBY_ROOT)
                onComplete()
            }
        }
    }

    fun loadLibraryPage() {
        requireWebView().loadUrl(LIBBY_SHELF)
    }

    fun openLoan(loanUrl: String) {
        require(loanUrl.startsWith("https://libbyapp.com/open/loan/")) {
            "Expected a Libby loan URL"
        }
        requireWebView().loadUrl(loanUrl)
    }

    /**
     * Scrolls the currently-loaded page to the bottom. Call this before scraping the
     * shelf: Libby's shelf list can render lazily as you scroll, so a loan further
     * down a long shelf (holds and loans aren't necessarily sorted by due date) could
     * be genuinely absent from the DOM -- and therefore invisible to
     * [scrapeAudiobookLoans] -- even though it's fully visible to a person scrolling
     * the same page in a real browser.
     */
    fun scrollToBottom() {
        requireWebView().evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
    }

    /**
     * Re-navigates the hidden WebView to the shelf and polls -- scrolling further
     * each pass -- until [titleId]'s own `.title-tile` actually appears in the DOM,
     * or gives up. This is what PlayerDebugScreen's Manage Tags flow uses to resync
     * before acting on an owned loan (see `manageTagsResync`'s doc comment there).
     *
     * The version this replaces did a single `loadLibraryPage()` + a FIXED 3 scroll
     * passes (about 2.2s total) with no check that the tile had actually rendered
     * before declaring itself done -- confirmed via a real retest to still produce
     * the same "no-tile" failure openSavePanelForTitle reports, because that's
     * meaningfully less margin than the shelf hydration loop already proven
     * reliable elsewhere in this file (PlayerDebugScreen's Books-screen refresh,
     * which polls up to 16 scroll-and-rescrape passes before giving up on a long
     * shelf). This mirrors that same depth, but checks for one specific tile by id
     * instead of rescraping the whole shelf each pass, since Manage Tags only needs
     * to know about the one title it's about to act on.
     */
    suspend fun resyncLoanShelfForTitle(titleId: String): Boolean {
        loadLibraryPage()
        delay(800)

        val tilePresentScript = """
            (() => {
                const tiles = Array.from(document.querySelectorAll('.title-tile'));
                const found = tiles.some((t) => (t.className || '').includes('data-title_$titleId'));
                return JSON.stringify({ found });
            })();
        """.trimIndent()

        repeat(16) {
            val present = evaluateJs(tilePresentScript)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optBoolean("found") == true
            if (present) return true
            scrollToBottom()
            delay(400)
        }
        return false
    }


    fun scrapeAudiobookLoans(onResult: (List<LoanItem>) -> Unit) {
        val script = """
            (() => {
                const tiles = Array.from(document.querySelectorAll(
                    '.title-tile.data-tile-class_loan.data-title-tile-format_audiobook'
                ));

                const loans = tiles.map(tile => {
                    const classText = tile.className || '';
                    const titleIdMatch = classText.match(/data-title_(\d+)/);
                    const titleId = titleIdMatch ? titleIdMatch[1] : '';

                    // NOTE: checked against a real captured tile (Ghost Eaters, a normal
                    // non-expiring loan) -- its "Open Audiobook" control is a plain
                    // <button>, not a link, so it has no href to read at all. This
                    // openLink lookup is dead code on every tile seen so far and the
                    // loanUrl always comes from the returnLink fallback below; it's left
                    // in only in case some tile layout genuinely does render Open
                    // Audiobook as a real link. Do not treat this as the fix for a
                    // missing loan -- see returnLink below for what tiles actually rely
                    // on today.
                    let loanUrl = '';
                    const openLink = tile.querySelector('a[href*="/open/loan/"]');
                    if (openLink) {
                        const href = openLink.getAttribute('href') || '';
                        loanUrl = href.startsWith('http') ? href : 'https://libbyapp.com' + href;
                    }
                    if (!loanUrl) {
                        // This is the ONLY source that has actually worked for any real
                        // tile so far. Confirmed root cause of the missing "Brigands &
                        // Breadknives" loan via a real device capture: an expiring loan's
                        // return link is NOT a plain .../return href -- Libby appends a
                        // query string to it (.../return?expiring=1), so the old
                        // [href$="/return"] selector (exact suffix match) never matched
                        // it at all, while every non-expiring loan's plain .../return
                        // href matched fine. Switched to [href*="/return"] (contains,
                        // not ends-with) so both shapes match; the loanMatch regex below
                        // already tolerates a trailing query string since it isn't
                        // anchored to end-of-string.
                        const returnLink = tile.querySelector(
                            'a[href*="/shelf/loans/"][href*="/return"]'
                        );
                        const returnHref = returnLink ? returnLink.getAttribute('href') || '' : '';
                        const loanMatch = returnHref.match(/\/shelf\/loans\/(\d+)-(\d+)\/return/);
                        const cardId = loanMatch ? loanMatch[1] : '';
                        const resolvedTitleId = loanMatch ? loanMatch[2] : titleId;
                        loanUrl = cardId && resolvedTitleId
                            ? 'https://libbyapp.com/open/loan/' + cardId + '/' + resolvedTitleId
                            : '';
                    }

                    const title = (tile.querySelector('.title-tile-title')?.textContent || '').trim();
                    const author = (tile.querySelector('.title-tile-author')?.textContent || '').trim();
                    const cover = tile.querySelector('img[data-cover-id]')?.src || '';
                    // Not scoped inside the return link anymore -- same reasoning as
                    // loanUrl above, this due-date badge shouldn't depend on that link
                    // existing at all.
                    const due = (tile.querySelector('.title-tile-journey-link')?.textContent || '').trim();
                    const progress = (tile.querySelector('.title-tile-journey-percent')?.textContent || '').trim();

                    return { title, author, loanUrl, coverUrl: cover, dueText: due, progressText: progress };
                }).filter(item => item.title && item.loanUrl);

                return JSON.stringify(loans);
            })();
        """.trimIndent()

        requireWebView().evaluateJavascript(script) { raw ->
            try {
                val decoded = decodeJavascriptString(raw) ?: return@evaluateJavascript onResult(emptyList())
                val array = JSONArray(decoded)
                val items = buildList {
                    for (index in 0 until array.length()) {
                        val json = array.getJSONObject(index)
                        add(
                            LoanItem(
                                title = json.optString("title"),
                                author = json.optString("author"),
                                loanUrl = json.optString("loanUrl"),
                                coverUrl = json.optString("coverUrl"),
                                dueText = json.optString("dueText"),
                                progressText = json.optString("progressText"),
                            )
                        )
                    }
                }
                Log.d(TAG, "scrapeAudiobookLoans: ${items.size} loans")
                onResult(items)
            } catch (_: Exception) {
                Log.e(TAG, "scrapeAudiobookLoans failed")
                onResult(emptyList())
            }
        }
    }

    // --- Holds ----------------------------------------------------------------------
    //
    // Holds live on their own /shelf/holds route rather than the plain /shelf route
    // loans use -- per Libby's own help docs, viewing holds is "Shelf -> Holds", a
    // separate tab/route, not just a different filter over the same loans page.
    // Everything below mirrors scrapeAudiobookLoans/returnLoan/renewLoan's proven
    // tile-match-by-class-id + click + reverify-by-rescrape shape, but the exact
    // selectors for the Holds tab (tile class, cancel/suspend link shapes, button
    // text) are NOT confirmed against a real captured page the way loans/return were
    // -- this is best-effort from Libby's help docs, same situation Renew shipped
    // in, and will likely need one round of real-device refinement.

    fun loadHoldsPage() {
        requireWebView().loadUrl(LIBBY_SHELF_HOLDS)
    }

    fun scrapeHolds(onResult: (List<HoldItem>) -> Unit) {
        val script = """
            (() => {
                const tiles = Array.from(document.querySelectorAll(
                    '.title-tile.data-tile-class_hold.data-title-tile-format_audiobook'
                ));

                const holds = tiles.map(tile => {
                    const classText = tile.className || '';
                    const titleIdMatch = classText.match(/data-title_(\d+)/);
                    const titleId = titleIdMatch ? titleIdMatch[1] : '';

                    // cardId has no "open" URL to recover it from (unlike a loan), so
                    // the cancel/suspend link is the only place to read it from --
                    // same /shelf/holds/{cardId}-{titleId}/... shape loans use for
                    // their own return/renew links.
                    let cardId = '';
                    const actionLink = tile.querySelector(
                        'a[href*="/shelf/holds/"][href*="/cancel"], ' +
                        'a[href*="/shelf/holds/"][href*="/suspend"]'
                    );
                    const actionHref = actionLink ? actionLink.getAttribute('href') || '' : '';
                    const idMatch = actionHref.match(/\/shelf\/holds\/(\d+)-(\d+)\//);
                    if (idMatch) {
                        cardId = idMatch[1];
                    }

                    const title = (tile.querySelector('.title-tile-title')?.textContent || '').trim();
                    const author = (tile.querySelector('.title-tile-author')?.textContent || '').trim();
                    const cover = tile.querySelector('img[data-cover-id]')?.src || '';
                    // Same journey-link class loans use for their due-date badge --
                    // Libby reuses one tile component for both, so this most likely
                    // doubles as the hold's status text ("Ready for borrowing...",
                    // "Estimated wait...", "#N in line...").
                    const status = (tile.querySelector('.title-tile-journey-link')?.textContent || '').trim();
                    const isReady = /ready/i.test(status);

                    return { title, author, cardId, titleId, coverUrl: cover, status, isReady };
                }).filter(item => item.title && item.titleId);

                return JSON.stringify(holds);
            })();
        """.trimIndent()

        requireWebView().evaluateJavascript(script) { raw ->
            try {
                val decoded = decodeJavascriptString(raw) ?: return@evaluateJavascript onResult(emptyList())
                val array = JSONArray(decoded)
                val items = buildList {
                    for (index in 0 until array.length()) {
                        val json = array.getJSONObject(index)
                        add(
                            HoldItem(
                                title = json.optString("title"),
                                author = json.optString("author"),
                                cardId = json.optString("cardId"),
                                titleId = json.optString("titleId"),
                                coverUrl = json.optString("coverUrl"),
                                status = json.optString("status"),
                                isReady = json.optBoolean("isReady"),
                            )
                        )
                    }
                }
                Log.d(TAG, "scrapeHolds: ${items.size} holds")
                onResult(items)
            } catch (_: Exception) {
                Log.e(TAG, "scrapeHolds failed")
                onResult(emptyList())
            }
        }
    }

    /**
     * Cancels a hold. Per Libby's help docs: Shelf -> Holds -> Manage Hold -> Cancel
     * Hold -> Cancel Hold again to confirm. Tries a direct cancel link on the tile
     * first (mirroring returnLoan's proven shortcut), falling back to the documented
     * "Manage Hold" control.
     */
    suspend fun cancelHold(cardId: String, titleId: String): Boolean {
        if (!requireWebView().url.orEmpty().contains("/shelf/holds")) {
            loadHoldsPage()
            delay(1200)
        }
        scrollToBottom()
        delay(400)

        val clickCancelEntryScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const tiles = Array.from(document.querySelectorAll('.title-tile'));
                const tile = tiles.find(t => (t.className || '').includes('data-title_$titleId'));
                if (!tile) return JSON.stringify({ found: false });

                const directLink = tile.querySelector('a[href*="/shelf/holds/"][href*="/cancel"]');
                if (directLink) {
                    directLink.click();
                    return JSON.stringify({ found: true });
                }

                const manageControl = Array.from(tile.querySelectorAll('button, a')).find((el) => {
                    return clean(el.textContent).toLowerCase() === 'manage hold';
                });
                if (manageControl) {
                    manageControl.click();
                    return JSON.stringify({ found: true });
                }
                return JSON.stringify({ found: false });
            })();
        """.trimIndent()
        val enteredFlow = evaluateJs(clickCancelEntryScript)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        if (!enteredFlow) return false

        val clickConfirmCancelScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const target = Array.from(document.querySelectorAll('button, a')).find((el) => {
                    const text = clean(el.textContent).toLowerCase();
                    return text === 'cancel hold' || text === 'confirm' || text === 'yes';
                });
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        repeat(10) {
            delay(500)
            evaluateJs(clickConfirmCancelScript)
        }

        delay(800)
        loadHoldsPage()
        delay(800)
        scrollToBottom()
        delay(400)

        return kotlin.coroutines.suspendCoroutine { continuation ->
            scrapeHolds { holds ->
                continuation.resume(holds.none { it.cardId == cardId && it.titleId == titleId })
            }
        }
    }

    /**
     * Suspends a hold (keeps your place in line but delays delivery). Per Libby's
     * help docs: a ready-to-borrow hold has a direct Suspend button; a still-waiting
     * hold needs Manage Hold -> Suspend Hold -> Suspend Hold again to confirm.
     *
     * A suspended hold stays on the shelf, so success is judged by its status text
     * changing to mention "suspend" rather than by the hold disappearing (same
     * before/after-comparison idea renewLoan uses for a loan's due date).
     */
    suspend fun suspendHold(cardId: String, titleId: String): Boolean {
        if (!requireWebView().url.orEmpty().contains("/shelf/holds")) {
            loadHoldsPage()
            delay(1200)
        }
        scrollToBottom()
        delay(400)

        val clickSuspendEntryScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const tiles = Array.from(document.querySelectorAll('.title-tile'));
                const tile = tiles.find(t => (t.className || '').includes('data-title_$titleId'));
                if (!tile) return JSON.stringify({ found: false });

                const directSuspend = Array.from(tile.querySelectorAll('button, a')).find((el) => {
                    return clean(el.textContent).toLowerCase() === 'suspend';
                });
                if (directSuspend) {
                    directSuspend.click();
                    return JSON.stringify({ found: true });
                }

                const directLink = tile.querySelector('a[href*="/shelf/holds/"][href*="/suspend"]');
                if (directLink) {
                    directLink.click();
                    return JSON.stringify({ found: true });
                }

                const manageControl = Array.from(tile.querySelectorAll('button, a')).find((el) => {
                    return clean(el.textContent).toLowerCase() === 'manage hold';
                });
                if (manageControl) {
                    manageControl.click();
                    return JSON.stringify({ found: true });
                }
                return JSON.stringify({ found: false });
            })();
        """.trimIndent()
        val enteredFlow = evaluateJs(clickSuspendEntryScript)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        if (!enteredFlow) return false

        val clickConfirmSuspendScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const target = Array.from(document.querySelectorAll('button, a')).find((el) => {
                    const text = clean(el.textContent).toLowerCase();
                    return text === 'suspend hold' || text === 'confirm' || text === 'yes';
                });
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        repeat(10) {
            delay(500)
            evaluateJs(clickConfirmSuspendScript)
        }

        delay(800)
        loadHoldsPage()
        delay(800)
        scrollToBottom()
        delay(400)

        return kotlin.coroutines.suspendCoroutine { continuation ->
            scrapeHolds { holds ->
                val match = holds.firstOrNull { it.cardId == cardId && it.titleId == titleId }
                continuation.resume(match?.status?.contains("suspend", ignoreCase = true) == true)
            }
        }
    }

    // --- Tags (Saved Tags) ------------------------------------------------------------
    //
    // Libby's own tagging feature: tapping the "Save" control on any title tile opens a
    // dialog titled "Save Title to a Tag" listing every existing tag as a row (checked
    // if the title already has it) plus a "+ New Tag..." row at the top. All of this is
    // now confirmed via real device/browser capture, not guessed:
    //   - The tags list itself is a real, directly loadable route: LIBBY_TAGS
    //     ("https://libbyapp.com/tags") -- confirmed via the address bar after a real
    //     click-through, and via Libby's own router log landing there on direct load.
    //     (An earlier guess at "/shelf/tags" was confirmed WRONG the same way -- the
    //     router logged "Unused segment: tags" and stayed on plain Shelf.)
    //   - Each tag row is `a.screen-tags-list-item-link[href="/tags/tag/{slug}"]`,
    //     where `{slug}` is the URL-encoded tag name (works for emoji names too).
    //   - A tag's own detail page is directly loadable at that same href
    //     (`https://libbyapp.com/tags/tag/{slug}`), and its titles use
    //     `.title-plank-*` markup -- NOT `.title-tile` (the class used on the Shelf,
    //     Holds, and Search) -- see [scrapeTitlesForTag].
    //   - The tag's own "ACTIONS" button is tag MANAGEMENT (Rename/Export/Delete), not
    //     creation -- confirmed via capture, correcting an earlier wrong assumption.
    //   - Tag CREATION only exists inside a specific title's Save dialog: "+ New
    //     Tag..." there opens a second dialog (name + optional description + CREATE).
    //     There is no standalone way to create a tag without tagging a title at the
    //     same time -- see [createTagForTitle].
    // Still unconfirmed (best-effort, pending one more real-device check): the exact
    // markup of each row/checkbox inside the "Save Title to a Tag" dialog itself, used
    // by [scrapeTagMembership]/[toggleTagForTitle]/[createTagForTitle] below -- those
    // match by visible text rather than a specific class name, since the real class
    // names there haven't been read from outerHTML yet (only seen in screenshots).

    /** Navigates directly to the account's Tags list -- a real, confirmed Libby route
     *  (see the section comment above for how that was confirmed). */
    fun loadTagsPage() {
        requireWebView().loadUrl(LIBBY_TAGS)
    }

    /** Navigates directly to a single tag's detail page. [tagId] is the raw URL
     *  segment captured by [scrapeTags] (the part of its row's href after
     *  `/tags/tag/`) -- already URL-encoded as Libby itself renders it, so it's
     *  used as-is rather than re-encoded. */
    fun loadTagPage(tagId: String) {
        requireWebView().loadUrl("$LIBBY_TAGS/tag/$tagId")
    }

    /**
     * Selects Libby's own "Oldest" sort on the already-loaded Tags list page
     * before it's scraped, so newly created tags consistently land at the END
     * of the list instead of wherever the page's own default order happens to
     * put them. Confirmed via real captured markup: a
     * `button[aria-label="Open Filters Dialog"]` on the Tags page opens a
     * filter sheet with a "Sort By" cloud of buttons, one of them
     * `aria-label="Oldest. Sort."`; picking it still needs a visible
     * "SHOW n RESULTS" button tapped to actually apply it and close the sheet
     * (matched by its own visible text, same as how this file already matches
     * Borrow/Suspend/etc. buttons elsewhere, since no class name for it has
     * been captured). Deliberately best-effort and silent: this is a display
     * preference, not a correctness issue, so if any step doesn't find its
     * target (a future Libby redesign, say), [scrapeTags] still runs -- just
     * in whatever order the page was already in -- rather than failing the
     * whole refresh over sort order.
     */
    suspend fun sortTagsByOldest() {
        val openScript = """
            (() => {
                const target = document.querySelector('button[aria-label="Open Filters Dialog"]');
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        val opened = evaluateJs(openScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        if (!opened) return

        val oldestScript = """
            (() => {
                const target = document.querySelector('button[aria-label="Oldest. Sort."]');
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        var pickedOldest = false
        for (attempt in 0 until 8) {
            delay(250)
            pickedOldest = evaluateJs(oldestScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optBoolean("found") == true
            if (pickedOldest) break
        }
        if (!pickedOldest) return

        val applyScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const target = Array.from(document.querySelectorAll('button')).find((el) =>
                    /^show \d+ results?${'$'}/i.test(clean(el.textContent))
                );
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        delay(250)
        evaluateJs(applyScript)
        delay(500)
    }

    /** Scrapes the account's own tags from the Tags list page ([loadTagsPage]),
     *  using the confirmed real markup: each row is
     *  `a.screen-tags-list-item-link[href="/tags/tag/{slug}"]` wrapping a
     *  `.tag-plank`. Confirmed via a real captured outerHTML dump (from the Save
     *  dialog's own tag rows, which reuse this exact same `.tag-plank` component)
     *  that `.tag-line-item` holds TWO sibling spans: one `[data-access="spoken"]`
     *  for screen readers (containing a "Tag: " prefix, e.g. "Tag: Interested") and
     *  one `[data-access="visual"]` holding just the plain visible name (e.g.
     *  "Interested") with no prefix and no duplication at all. Earlier attempts at
     *  this tried to reconstruct the name from raw textContent (which concatenates
     *  both spans) using `data-tag-name-length` to slice off the right number of
     *  characters -- unnecessarily complicated, and wrong besides, once it turned
     *  out `data-tag-name-length` counts a whole emoji as one unit while JS string
     *  slicing counts UTF-16 units. Reading `[data-access="visual"]` directly
     *  sidesteps all of that. The same confirmed component also has the title count
     *  at `.tag-plank-count [data-access="visual"]` (falls back to the old guessed
     *  `[class*="count"]` selector if that's ever not present here). Also reads
     *  `.tag-plank-behavior` (e.g. "Regular Tag" or "Smart Tag: Borrowed") and
     *  `.tag-plank-description` -- both confirmed present on this same `.tag-plank`
     *  component from the real captured dump -- so [LibbyTag.displayName] has
     *  something readable to show instead of a bare emoji name. Both are optional
     *  (missing just means displayName falls back to the raw name), so this
     *  degrades safely if that assumption ever turns out wrong for some row. */
    fun scrapeTags(onResult: (List<LibbyTag>) -> Unit) {
        val script = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const rows = Array.from(document.querySelectorAll('a.screen-tags-list-item-link'));
                const tags = rows.map(row => {
                    const href = row.getAttribute('href') || '';
                    const idMatch = href.match(/\/tags\/tag\/([^/?]+)/);
                    const id = idMatch ? idMatch[1] : '';
                    const item = row.querySelector('.tag-line-item');
                    const visual = item ? item.querySelector('[data-access="visual"]') : null;
                    const name = clean((visual || item || row).textContent);
                    const countVisual = row.querySelector('.tag-plank-count [data-access="visual"]');
                    const countText = clean(
                        countVisual ? countVisual.textContent : row.querySelector('[class*="count"]')?.textContent
                    );
                    const countMatch = countText.match(/\d+/);
                    const titleCount = countMatch ? parseInt(countMatch[0], 10) : 0;
                    const behaviorNode = row.querySelector('.tag-plank-behavior');
                    const behavior = clean(behaviorNode ? behaviorNode.textContent : '');
                    const descriptionNode = row.querySelector('.tag-plank-description');
                    const description = clean(descriptionNode ? descriptionNode.textContent : '');
                    return { id, name, titleCount, behavior, description };
                }).filter(item => item.id && item.name);

                return JSON.stringify(tags);
            })();
        """.trimIndent()

        requireWebView().evaluateJavascript(script) { raw ->
            try {
                val decoded = decodeJavascriptString(raw) ?: return@evaluateJavascript onResult(emptyList())
                val array = JSONArray(decoded)
                val items = buildList {
                    for (index in 0 until array.length()) {
                        val json = array.getJSONObject(index)
                        add(
                            LibbyTag(
                                id = json.optString("id"),
                                name = json.optString("name"),
                                titleCount = json.optInt("titleCount"),
                                behavior = json.optString("behavior"),
                                description = json.optString("description"),
                            )
                        )
                    }
                }
                Log.d(TAG, "scrapeTags: ${items.size} tags")
                onResult(items)
            } catch (_: Exception) {
                Log.e(TAG, "scrapeTags failed")
                onResult(emptyList())
            }
        }
    }

    private suspend fun scrapeTagsSuspend(): List<LibbyTag> =
        kotlin.coroutines.suspendCoroutine { continuation ->
            scrapeTags { continuation.resume(it) }
        }

    /**
     * Repeatedly runs [read] until two consecutive results come back identical (or
     * [maxAttempts] is exhausted, in which case the last result is returned as-is).
     * This replaces guessing at ever-longer fixed delays for "the DOM hasn't finished
     * settling yet" symptoms -- the single most common root cause of real-device bugs
     * in this whole file -- with something that actually waits for the thing it's
     * reading to stop changing, the same idea [refreshTags] in PlayerDebugScreen
     * already uses for a freshly-sorted tag's title count. [T] needs real structural
     * equality (data classes, Set, etc.) for the comparison to mean anything.
     */
    private suspend fun <T> pollUntilStable(
        maxAttempts: Int = 5,
        delayMs: Long = 300,
        read: suspend () -> T,
    ): T {
        var current = read()
        repeat(maxAttempts) {
            delay(delayMs)
            val next = read()
            if (next == current) return next
            current = next
        }
        return current
    }

    /** Set by [openTagActionsChoice], [renameTag], and [deleteTag] whenever one of
     *  their steps fails, holding a short machine-readable reason for exactly which
     *  step didn't find what it expected -- the same idea as [lastSavePanelDiagnostic]
     *  and for the same reason: every bug report on this flow so far has been a
     *  screenshot of the generic "Could not confirm..." message, not a device log, so
     *  this field is what the UI shows instead (see PlayerDebugScreen's
     *  onRename/onDelete handlers). Cleared whenever a call gets all the way through
     *  successfully. */
    var lastTagActionDiagnostic: String = ""
        private set

    /**
     * Opens [tagId]'s own detail page, taps its "ACTIONS" button, then taps a
     * specific choice from the popover that opens -- the one piece shared by
     * [renameTag] and [deleteTag] below. [choiceClass] is one of the two confirmed
     * real classes on that popover's `<a>` items: `screen-tags-tag-rename` or
     * `screen-tags-tag-delete` (a third, `screen-tags-tag-export`, exists too but
     * nothing in this app uses it). The "ACTIONS" button itself has no captured
     * class -- it's matched by its visible text instead, the same fallback this
     * file already uses successfully for several other single, unambiguous
     * controls (Manage Loan, Open Audiobook, etc.) -- call out to get its real
     * class if this specific click ever turns up as the failure point.
     */
    private suspend fun openTagActionsChoice(tagId: String, choiceClass: String): Boolean {
        loadTagPage(tagId)
        delay(900)

        // Matches by `.includes('actions')` rather than an exact `=== 'actions'` --
        // and polls for it rather than trusting one read after a flat delay -- for
        // the same two reasons already proven elsewhere in this file: Libby
        // sometimes renders a control's label as duplicated spoken/visual text
        // (confirmed on the old Save-button bug, see openSavePanelForTitle's doc
        // comment), which breaks an exact match, and a page transition can still be
        // rendering after a single fixed wait (confirmed by the choice-click poll
        // loop just below, which already needed this same treatment). Both fixes
        // applied here instead of guessing at a third delay number, after
        // "actions-button-not-found" turned up as the real, confirmed failure point
        // on a real device for both Rename and Delete.
        val clickActionsScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const target = Array.from(document.querySelectorAll('button, a')).find((el) =>
                    clean(el.textContent).toLowerCase().includes('actions')
                );
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        var openedActions = false
        for (attempt in 0 until 8) {
            delay(250)
            openedActions = evaluateJs(clickActionsScript)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optBoolean("found") == true
            if (openedActions) break
        }
        if (!openedActions) {
            lastTagActionDiagnostic = "actions-button-not-found"
            return false
        }

        val clickChoiceScript = """
            (() => {
                const target = document.querySelector('.$choiceClass');
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        var opened = false
        for (attempt in 0 until 8) {
            delay(250)
            opened = evaluateJs(clickChoiceScript)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optBoolean("found") == true
            if (opened) break
        }
        if (!opened) {
            lastTagActionDiagnostic = "$choiceClass-not-found"
            return false
        }
        lastTagActionDiagnostic = ""
        return true
    }

    /**
     * Renames [tagId] to [newName]. Confirmed via a real captured outerHTML dump
     * that "Rename Tag" opens the EXACT SAME name-entry form [createTagForTitle]
     * already fills and submits for a brand new tag (`input[placeholder="Name of
     * tag..."]` inside `.configure-tag-name`, submitted via
     * `.configure-tag-name-submit`) -- Libby reuses one component for both create
     * and rename, just with different framing text around it. That's why this
     * reuses the same setValue-and-dispatch-input-event technique rather than
     * guessing at a different one.
     */
    suspend fun renameTag(tagId: String, newName: String): Boolean {
        if (!openTagActionsChoice(tagId, "screen-tags-tag-rename")) return false
        delay(500)

        val escapedName = newName.replace("\\", "\\\\").replace("'", "\\'")
        val fillScript = """
            (() => {
                const setValue = (el, value) => {
                    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    setter.call(el, value);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                };
                const nameInput = document.querySelector('input[placeholder="Name of tag..."]');
                if (!nameInput) return JSON.stringify({ found: false });
                setValue(nameInput, '$escapedName');
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        val filled = evaluateJs(fillScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        if (!filled) {
            lastTagActionDiagnostic = "rename-input-not-found"
            return false
        }

        val clickDoneScript = """
            (() => {
                const target = document.querySelector('.configure-tag-name-submit');
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        val clickedDone = evaluateJs(clickDoneScript)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        if (!clickedDone) {
            lastTagActionDiagnostic = "rename-submit-not-found"
            return false
        }
        delay(900)

        // scrapeTagsSuspend()'s underlying scrapeTags() reads whatever page is
        // CURRENTLY loaded -- it never navigates anywhere itself (see refreshTags's
        // own loadTagsPage()+delay() pairing before it ever calls this). Right after
        // submitting a rename we're still on this tag's own detail page, not the
        // Tags list, so without this the verify step was scraping the wrong page
        // and always coming back empty -- confirmed by a real "rename-not-verified;
        // now saw: []" report, which is what turned up this bug: the rename may
        // well have been succeeding this whole time, with only the check itself
        // broken. Navigating here first, same as refreshTags does, fixes that.
        loadTagsPage()
        delay(1000)
        val found = scrapeTagsSuspend()
        val renamed = found.any { it.name.equals(newName, ignoreCase = true) }
        if (!renamed) {
            lastTagActionDiagnostic = "rename-not-verified; now saw: ${found.map { it.name }}"
        } else {
            lastTagActionDiagnostic = ""
        }
        return renamed
    }

    /**
     * Deletes [tagId]. Confirmed via a real captured outerHTML dump that "Delete
     * Tag" opens a danger-styled confirm popover with one specific real control --
     * `button.popover-danger-confirm` ("Yes, delete tag") -- which is driven
     * through here rather than skipped, since this hidden-WebView confirm is
     * invisible to the user: the only confirmation they actually see is Livrospeak's
     * own ConfirmDeleteTagScreen before this function is ever called.
     */
    suspend fun deleteTag(tagId: String): Boolean {
        if (!openTagActionsChoice(tagId, "screen-tags-tag-delete")) return false
        delay(400)

        val clickConfirmScript = """
            (() => {
                const target = document.querySelector('.popover-danger-confirm');
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        var confirmed = false
        for (attempt in 0 until 8) {
            delay(250)
            confirmed = evaluateJs(clickConfirmScript)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optBoolean("found") == true
            if (confirmed) break
        }
        if (!confirmed) {
            lastTagActionDiagnostic = "delete-confirm-not-found"
            return false
        }
        delay(900)

        // Same fix as renameTag's own, and for the same reason -- see its comment
        // just above for the full explanation. Without this, an apparent "success"
        // here was actually meaningless: scraping the wrong (non-list) page reads
        // back empty regardless of whether the tag was really deleted, so
        // `found.none { ... }` was trivially true either way. Confirmed by a real
        // report where this reported success and Livrospeak's own (wrongly-scraped)
        // Saved Tags list no longer showed the tag, yet the same tag was confirmed
        // still present on the real libby.com site -- proof the tag was never
        // actually deleted and the apparent list change was just this same
        // wrong-page scrape coincidentally reading back empty.
        loadTagsPage()
        delay(1000)
        val found = scrapeTagsSuspend()
        val deleted = found.none { it.id == tagId }
        if (!deleted) {
            lastTagActionDiagnostic = "delete-not-verified; still saw: ${found.map { it.id }}"
        } else {
            lastTagActionDiagnostic = ""
        }
        return deleted
    }

    /** Scrapes the titles under whatever tag detail page is currently loaded (call
     *  [loadTagPage] first and give the page a moment to render). Confirmed via real
     *  capture that a NOT-currently-borrowed tagged title uses `.title-plank-*`
     *  markup -- the title link's href is a details-page URL shaped like
     *  `/tags/tag/similar-{titleId}/page-1/{titleId}`, from which titleId is pulled
     *  via its trailing digits. Deliberately does NOT scrape ownership/availability
     *  from that markup: unlike `.title-tile`, there's no "Open Audiobook"/"Borrow"
     *  text on a plank to read it from -- see [TaggedTitle]'s doc comment. Callers
     *  cross-reference titleId against the real loans list instead.
     *
     *  Also merges in any `.title-tile` entries found on the same page (the same
     *  markup [scrapeAudiobookLoans] reads) -- a real-device test turned up a tag
     *  whose title count didn't match how many titles this page actually showed,
     *  which best matches Libby rendering a tag's currently-owned (borrowed/held)
     *  titles with the Shelf's own tile markup instead of a plain plank. Harmless if
     *  that theory is wrong: this selector simply matches nothing on a tag with no
     *  owned titles. De-duplicated by titleId in case a title is ever both. */
    fun scrapeTitlesForTag(onResult: (List<TaggedTitle>) -> Unit) {
        val script = """
            (() => {
                const seen = new Set();
                const titles = [];

                const biblios = Array.from(document.querySelectorAll('h3.title-plank-biblio'));
                biblios.forEach(biblio => {
                    const link = biblio.querySelector('a.title-plank-action[href]');
                    const href = (link && link.getAttribute('href')) || '';
                    const idMatch = href.match(/(\d+)\s*$/);
                    const titleId = idMatch ? idMatch[1] : '';
                    const title = (biblio.querySelector('.title-plank-title')?.textContent || '').trim();
                    const plank = biblio.closest('[class*="title-plank"]') || biblio.parentElement;
                    const author = (plank?.querySelector('.title-plank-author')?.textContent || '').trim();
                    const cover = plank?.querySelector('img[data-cover-id]')?.src || '';
                    if (title && titleId && !seen.has(titleId)) {
                        seen.add(titleId);
                        titles.push({ titleId, title, author, coverUrl: cover });
                    }
                });

                const tiles = Array.from(document.querySelectorAll('.title-tile'));
                tiles.forEach(tile => {
                    const classText = tile.className || '';
                    const titleIdMatch = classText.match(/data-title_(\d+)/);
                    const titleId = titleIdMatch ? titleIdMatch[1] : '';
                    const title = (tile.querySelector('.title-tile-title')?.textContent || '').trim();
                    const author = (tile.querySelector('.title-tile-author')?.textContent || '').trim();
                    const cover = tile.querySelector('img[data-cover-id]')?.src || '';
                    if (title && titleId && !seen.has(titleId)) {
                        seen.add(titleId);
                        titles.push({ titleId, title, author, coverUrl: cover });
                    }
                });

                return JSON.stringify(titles);
            })();
        """.trimIndent()

        requireWebView().evaluateJavascript(script) { raw ->
            try {
                val decoded = decodeJavascriptString(raw) ?: return@evaluateJavascript onResult(emptyList())
                val array = JSONArray(decoded)
                val items = buildList {
                    for (index in 0 until array.length()) {
                        val json = array.getJSONObject(index)
                        add(
                            TaggedTitle(
                                titleId = json.optString("titleId"),
                                title = json.optString("title"),
                                author = json.optString("author"),
                                coverUrl = json.optString("coverUrl"),
                            )
                        )
                    }
                }
                Log.d(TAG, "scrapeTitlesForTag: ${items.size} titles")
                onResult(items)
            } catch (_: Exception) {
                Log.e(TAG, "scrapeTitlesForTag failed")
                onResult(emptyList())
            }
        }
    }

    /**
     * Creates a brand new tag named [name] (with optional [description]) and applies
     * it to [titleId] in the same step. Confirmed via real capture that this is the
     * ONLY way Libby lets you create a tag -- there's no standalone creation UI (the
     * Tags list's own "ACTIONS" button is Rename/Export/Delete, not create); you must
     * be tagging a specific title, via its Save dialog's "+ New Tag..." row, which
     * opens a second dialog (name + optional description + CREATE). This is why the
     * old standalone Tags-list "+ New Tag" entry point was removed in favor of
     * offering creation only from within a title's Manage Tags screen. All three
     * selectors here are from a real captured outerHTML dump of both dialogs (not
     * guessed): the "+ New Tag..." control is `.dialog-tag-add-new-tag-button`, the
     * name field is `input[placeholder="Name of tag..."]`, the description field is
     * `input[placeholder="Description..."]`, and the submit control is
     * `.configure-tag-name-submit`. Success is judged the same way the rest of this
     * file judges a mutation -- not by trusting the click, but by re-reading the
     * title's tag membership afterward and confirming the new tag now actually
     * appears in it.
     */
    suspend fun createTagForTitle(titleId: String, name: String, description: String = ""): Boolean {
        if (!openSavePanelForTitle(titleId)) return false

        val clickNewTagScript = """
            (() => {
                const target = document.querySelector('.dialog-tag-add-new-tag-button');
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        val openedCreateUi = evaluateJs(clickNewTagScript)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        if (!openedCreateUi) {
            closeSavePanel()
            return false
        }
        delay(500)

        val escapedName = name.replace("\\", "\\\\").replace("'", "\\'")
        val escapedDescription = description.replace("\\", "\\\\").replace("'", "\\'")
        val fillScript = """
            (() => {
                const setValue = (el, value) => {
                    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    setter.call(el, value);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                };
                const nameInput = document.querySelector('input[placeholder="Name of tag..."]');
                if (!nameInput) return JSON.stringify({ found: false });
                setValue(nameInput, '$escapedName');
                if ('$escapedDescription') {
                    const descInput = document.querySelector('input[placeholder="Description..."]');
                    if (descInput) setValue(descInput, '$escapedDescription');
                }
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        val filled = evaluateJs(fillScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        if (!filled) {
            closeSavePanel()
            return false
        }

        val clickCreateScript = """
            (() => {
                const target = document.querySelector('.configure-tag-name-submit');
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        evaluateJs(clickCreateScript)
        delay(800)
        // Dismiss whatever's left open (the creation dialog, and/or the Save dialog
        // underneath it) before scrapeTagMembership tries to reopen a clean one.
        closeSavePanel()
        delay(300)

        val membership = scrapeTagMembership(titleId)
        return membership.any { it.equals(name, ignoreCase = true) }
    }

    /**
     * Reads BOTH the full catalog of existing tag names and which of them [titleId]
     * already belongs to, in a single pass over its own Save/tag dialog -- without
     * ever navigating the hidden WebView away from whatever page the title's tile is
     * on. This replaces a flow that used to separately call [loadTagsPage] +
     * [scrapeTags] to build the catalog whenever it wasn't already cached: confirmed
     * via real device testing that doing so navigated away to the Tags list page and
     * never back, so every later toggle or create click in that same session failed
     * to find the title's tile at all (it was no longer on screen anywhere). The
     * Save dialog already lists every tag as its own row regardless of membership,
     * so there's no need to visit the Tags list for this -- only the standalone
     * Saved Tags screen (via [scrapeTags]) still needs that, since it's the one
     * place that shows each tag's real title count, which this dialog doesn't.
     * Returned [LibbyTag]s use the tag's own name as [LibbyTag.id] (there's no real
     * id available from this dialog, only from the Tags list's own row links) --
     * fine here since callers only use these for display and to re-identify a tag
     * by name when toggling, not to navigate anywhere with [loadTagPage].
     */
    suspend fun scrapeSaveDialogTags(titleId: String): Pair<List<LibbyTag>, Set<String>> {
        if (!openSavePanelForTitle(titleId)) return emptyList<LibbyTag>() to emptySet()
        // Confirmed via a real on-device diagnostic that this dialog's own
        // `aria-checked` state reads correctly for an owned loan (an earlier
        // attempt to read membership instead from a tag-chip row on the Manage
        // Loan panel never worked on-device -- that panel never renders the chips
        // at this device's actual screen width -- see git history if that's ever
        // worth revisiting on a wider-screen device).
        val script = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const buttons = Array.from(document.querySelectorAll('.dialog-tag-add-item-button'));
                const rowsData = buttons.map(btn => {
                    const item = btn.querySelector('.tag-line-item');
                    const visual = item ? item.querySelector('[data-access="visual"]') : null;
                    const name = clean((visual || item || btn).textContent);
                    const checked = btn.getAttribute('aria-checked') === 'true';
                    const behaviorNode = btn.querySelector('.tag-plank-behavior');
                    const behavior = clean(behaviorNode ? behaviorNode.textContent : '');
                    const descriptionNode = btn.querySelector('.tag-plank-description');
                    const description = clean(descriptionNode ? descriptionNode.textContent : '');
                    return { name, checked, behavior, description };
                }).filter(item => item.name);
                return JSON.stringify(rowsData);
            })();
        """.trimIndent()
        suspend fun read(): Pair<List<LibbyTag>, Set<String>> = evaluateJs(script)?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                val tagsList = mutableListOf<LibbyTag>()
                val checked = mutableSetOf<String>()
                for (index in 0 until array.length()) {
                    val json = array.getJSONObject(index)
                    val name = json.optString("name")
                    if (name.isBlank()) continue
                    tagsList.add(
                        LibbyTag(
                            id = name,
                            name = name,
                            titleCount = 0,
                            behavior = json.optString("behavior"),
                            description = json.optString("description"),
                        ),
                    )
                    if (json.optBoolean("checked")) checked.add(name)
                }
                tagsList.toList() to checked.toSet()
            }.getOrNull()
        } ?: (emptyList<LibbyTag>() to emptySet())
        val result = pollUntilStable { read() }
        closeSavePanel()
        return result
    }

    /**
     * Opens the Save/tag dialog for [titleId] on whatever page is currently loaded
     * (the title's tile must be rendered somewhere on it -- the shelf, search
     * results, or a tag's own detail view all qualify, though the detail view uses
     * `.title-plank-*` markup -- see the section comment above -- so this specifically
     * needs a `.title-tile` to be present, matching the Shelf/Search case). Shared by
     * [scrapeTagMembership], [toggleTagForTitle], [createTagForTitle], and
     * [scrapeSaveDialogTags] above -- every one of those still failed identically
     * even after the dialog-INTERNAL selectors were rewritten from a real captured
     * outerHTML dump, which pointed at this function (the one piece of code shared
     * by all of them) never actually opening the dialog in the first place. The doc
     * comment here used to claim the real control was `button.tag-button.halo` with
     * text "Save", but the code below was never actually updated to match that --
     * it was still doing an exact `text === 'save'` comparison, which Libby's own
     * "spoken"/"visual" duplicate-span text pattern (confirmed elsewhere in this
     * file, e.g. a tag's own name) can easily break if it also applies to this
     * button's rendered text. Now actually uses the confirmed class first, with the
     * loosened text/label match only as a fallback, and reports what it found on
     * the tile when both fail -- so if this guess is STILL wrong, the next log line
     * says exactly what's on the tile instead of needing another manual capture.
     *
     * That diagnostic is exactly what turned up the NEXT real bug: this consistently
     * failed with reason "no-tile" for both an owned loan and a search result, which
     * traced back to the shared hidden WebView not even being on the right page --
     * confirmed in code for the loan case specifically: opening a book to reach
     * ManageLoanScreen already eagerly navigates this same WebView straight to that
     * loan's own player page (see PlayerDebugScreen's openBook ->
     * LibbyWebPlayer.openLoan), which has no `.title-tile` elements on it at all.
     * Callers that know they're dealing with an owned loan now re-navigate to the
     * shelf themselves before calling anything in this section (see
     * PlayerDebugScreen's openManageTags(ensureOnShelf = true)) -- not done here,
     * since this function has no way to tell a not-yet-borrowed search result (which
     * must NOT be sent to the shelf, since it isn't there) from an owned loan.
     * What IS done here, for both cases: a couple of scroll passes before matching,
     * the same lazy-render lesson already confirmed for Tags/Holds -- a tile for a
     * title further down a long shelf or results list may simply not be in the DOM
     * yet otherwise.
     *
     * CONFIRMED (real DevTools capture of libbyapp.com/shelf, not a guess) that the
     * block above is only ever true for a SEARCH RESULT tile. An OWNED LOAN's tile
     * has no Save/Tag control on it at all -- the diagnostic candidate list for a
     * real loan tile showed exactly: the title-opening link, author text, cover
     * image, "Open Audiobook", "Manage Loan", the due-date badge, and the reading
     * percentage, and nothing tag-related. Retried with longer resync waits twice
     * (alpha19/alpha20) with zero effect, which is what proved this was never a
     * timing problem. The real path for an owned loan is one layer deeper: tapping
     * its tile's "Manage Loan" control opens a loan-actions panel (Return Early /
     * Renew Loan / a "Tags" action cell), captured as
     * `<div class="action-cell action-cell-tags"><button class="action-cell-head
     * halo">...<span role="text">Tags</span>...</button></div>` -- and tapping THAT
     * "Tags" button opens the exact same "Save Title to a Tag" dialog a search
     * result's direct Save button opens (confirmed identical
     * `.dialog-tag-add-item-button`/`.dialog-tag-add-new-tag-button` markup either
     * way), which is why nothing below this function needed to change at all.
     */
    private suspend fun openSavePanelForTitle(titleId: String): Boolean {
        repeat(2) {
            scrollToBottom()
            delay(400)
        }
        val initialScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const tiles = Array.from(document.querySelectorAll('.title-tile'));
                const tile = tiles.find(t => (t.className || '').includes('data-title_$titleId'));
                if (!tile) return JSON.stringify({ found: false, reason: 'no-tile' });
                let target = tile.querySelector('button.tag-button.halo');
                if (!target) {
                    target = Array.from(tile.querySelectorAll('button, a')).find((el) => {
                        const text = clean(el.textContent).toLowerCase();
                        const label = clean(el.getAttribute('aria-label')).toLowerCase();
                        return text.includes('save') || label.includes('save') || label.includes('tag');
                    });
                }
                if (target) {
                    target.click();
                    return JSON.stringify({ found: true, direct: true });
                }
                // No direct Save/Tag control on this tile -- try the owned-loan path
                // instead: tap "Manage Loan" to open its action panel, which has its
                // own "Tags" button (polled for separately below, since that panel
                // renders asynchronously).
                const manageLoan = Array.from(tile.querySelectorAll('button, a')).find((el) =>
                    clean(el.textContent).toLowerCase() === 'manage loan'
                );
                if (manageLoan) {
                    manageLoan.click();
                    return JSON.stringify({ found: true, direct: false });
                }
                const candidates = Array.from(tile.querySelectorAll('button, a')).slice(0, 12).map((el) => ({
                    text: clean(el.textContent),
                    aria: clean(el.getAttribute('aria-label')),
                    cls: clean(el.className),
                }));
                return JSON.stringify({ found: false, reason: 'no-button', candidates });
            })();
        """.trimIndent()
        val initial = evaluateJs(initialScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
        var opened = initial?.optBoolean("found") == true
        val direct = initial?.optBoolean("direct") == true

        if (opened && !direct) {
            // Clicked "Manage Loan" -- its panel (Return Early / Renew Loan / Tags)
            // renders asynchronously, so poll for the real "Tags" action cell
            // (`.action-cell-tags button.action-cell-head`, confirmed via a real
            // captured outerHTML dump) instead of guessing a fixed delay.
            //
            // This used to also snapshot a tag-chip row this same panel renders
            // (`.action-cell-tags .action-cell-body`) as a supposedly more reliable
            // membership source than the checkbox dialog below -- four fix attempts
            // across several versions (longer delays, poll-until-stable, fixing that
            // poll's own first-read timing, then a richer diagnostic) all failed
            // real-device retests. The diagnostic that finally explained it: on this
            // device's actual (360px) viewport, that chip row's container and body
            // both exist but the body is permanently an empty `<div>` -- Libby
            // simply never populates it at this width, independent of any timing.
            // Since this device's viewport never changes, that chip read can never
            // succeed here, so it's removed rather than left as dead code that
            // silently cost ~3 seconds on every open. The checkbox dialog below
            // (confirmed via its own matching diagnostic to read `aria-checked`
            // correctly on-device) is the sole membership source now.
            val tagsActionScript = """
                (() => {
                    const target = document.querySelector('.action-cell-tags button.action-cell-head');
                    if (!target) return JSON.stringify({ found: false });
                    target.click();
                    return JSON.stringify({ found: true });
                })();
            """.trimIndent()
            var clickedTags = false
            for (attempt in 0 until 10) {
                delay(300)
                clickedTags = evaluateJs(tagsActionScript)
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.optBoolean("found") == true
                if (clickedTags) break
            }
            opened = clickedTags
        }

        if (!opened) {
            val reason = initial?.optString("reason").orEmpty().ifBlank { "no-response" }
            // Logged for a device-attached debug session, but -- unlike that --
            // ALSO stored here so the UI itself can show it: every bug report in
            // this flow so far has come as a screenshot of the app, not a device
            // log, so a Log.d alone never actually reaches anyone trying to figure
            // out why create/toggle failed. See its use in PlayerDebugScreen's
            // onCreate/onToggleTag failure messages.
            lastSavePanelDiagnostic = "$reason${initial?.optJSONArray("candidates")?.let { "; saw: $it" }.orEmpty()}"
            Log.d(TAG, "openSavePanelForTitle($titleId) failed: $lastSavePanelDiagnostic")
        } else {
            lastSavePanelDiagnostic = ""
        }
        // The indirect (owned-loan) path just drove TWO successive UI transitions
        // (Manage Loan -> poll for the Tags panel -> click) instead of one direct
        // click, so the dialog that lands is fresher and its rows' aria-checked
        // state (what scrapeSaveDialogTags reads right after this returns) gets
        // less time to settle than a search result's single-click open gets with
        // the same flat delay -- confirmed via a real report that a current loan's
        // already-applied tag didn't show as "on" immediately after opening its tag
        // screen. Giving the indirect path extra margin here, rather than guessing
        // at a different read strategy in the caller.
        if (opened) delay(if (direct) 500 else 1200)
        return opened
    }

    /** Set by [openSavePanelForTitle] whenever it fails, holding a short reason
     *  (and, if available, what buttons/links it actually saw on the tile) --
     *  see its own doc comment for why this exists as a readable field rather
     *  than only a log line. Cleared on the next successful open. */
    var lastSavePanelDiagnostic: String = ""
        private set

    /** Best-effort dismissal of whatever Save/tag dialog(s) are currently open, if
     *  any, so they don't linger over the next page the WebView navigates to.
     *  Confirmed via real capture that the real dismiss control is
     *  `button.shibui-shade-dismiss-button[aria-label="Dismiss dialog"]` -- clicks
     *  every one found rather than just the first, since the "+ New Tag" creation
     *  dialog stacks on top of the Save dialog underneath it (both can be open at
     *  once), and dismissing just the top one would leave the other lingering. */
    private suspend fun closeSavePanel() {
        val script = """
            (() => {
                const closeBtns = Array.from(
                    document.querySelectorAll('.shibui-shade-dismiss-button, [aria-label="Dismiss dialog"]')
                );
                closeBtns.forEach((btn) => btn.click());
                return JSON.stringify({ ok: true, count: closeBtns.length });
            })();
        """.trimIndent()
        evaluateJs(script)
    }

    /**
     * Reads which of the account's tags already contain [titleId], by opening its
     * Save dialog and reading which rows come back with `aria-checked="true"`.
     * Returns tag NAMES (matching how [toggleTagForTitle] identifies a tag), not
     * ids, since the dialog only exposes each tag's visible name, not its id.
     * Confirmed via a real captured outerHTML dump that each tag row IS the control
     * itself -- `button.dialog-tag-add-item-button[role="checkbox"][aria-checked]`
     * -- not a wrapper around a separate `<input type="checkbox">`, which is why
     * this never worked before.
     */
    suspend fun scrapeTagMembership(titleId: String): Set<String> {
        if (!openSavePanelForTitle(titleId)) return emptySet()
        val script = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const buttons = Array.from(document.querySelectorAll('.dialog-tag-add-item-button'));
                const checked = buttons.filter((btn) => btn.getAttribute('aria-checked') === 'true').map((btn) => {
                    const item = btn.querySelector('.tag-line-item');
                    const visual = item ? item.querySelector('[data-access="visual"]') : null;
                    return clean((visual || item || btn).textContent);
                }).filter(Boolean);
                return JSON.stringify(checked);
            })();
        """.trimIndent()
        suspend fun read(): Set<String> = evaluateJs(script)?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map(array::getString).toSet()
            }.getOrNull()
        }.orEmpty()
        val names = pollUntilStable { read() }
        closeSavePanel()
        return names
    }

    /**
     * Toggles [titleId]'s membership in the tag named [tagName]. Confirmed via real
     * capture that each tag row in the Save dialog IS the clickable
     * `role="checkbox"` button itself (`.dialog-tag-add-item-button`) -- there's no
     * separate `<input type="checkbox">` inside it to click, which is why this
     * never actually toggled anything before. The caller decides which direction
     * this means by checking [scrapeTagMembership] first and re-checking it
     * afterward to confirm the toggle actually landed, the same before/after
     * comparison [suspendHold] uses for a hold's status text.
     */
    suspend fun toggleTagForTitle(titleId: String, tagName: String): Boolean {
        if (!openSavePanelForTitle(titleId)) return false
        val escapedName = tagName.replace("\\", "\\\\").replace("'", "\\'")
        val script = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const buttons = Array.from(document.querySelectorAll('.dialog-tag-add-item-button'));
                const target = buttons.find((btn) => {
                    const item = btn.querySelector('.tag-line-item');
                    const visual = item ? item.querySelector('[data-access="visual"]') : null;
                    const name = clean((visual || item || btn).textContent);
                    return name.toLowerCase() === '$escapedName'.toLowerCase();
                });
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        val toggled = evaluateJs(script)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        delay(400)
        closeSavePanel()
        return toggled
    }

    // --- Library Cards --------------------------------------------------------
    //
    // Confirmed over several rounds of real captured markup: the account Menu
    // screen lists each connected library as its own `<li class="summary-list-
    // library">` row with a `[aria-label="Manage Cards"]`-style button. Clicking
    // it is a real page navigation (to `interview/configure/cards#list-library-
    // cards`), landing on a dedicated screen that shows ONE library's card at a
    // time. That screen's own heading has a "Choose another library" control
    // (`[aria-label="Choose another library."]`) that opens a picker -- built
    // from the exact same `<li class="summary-list-library">` row shape as the
    // Menu screen, but keyed by a stable `data-item-id` and holding a `button.
    // summary-list-library-target` wrapping a `strong.summary-list-library-
    // name`. Picking a row switches which library's card the screen shows. The
    // card number itself renders as a `[data-access="visual"]` span that's
    // nothing but a run of 6+ digits (the same spoken/visual pattern used
    // everywhere else in this file).
    //
    // An earlier version of this function tried to return to the Menu screen's
    // own row list for every library in turn -- that only ever worked for the
    // first one, since by then the page had already navigated away to the cards
    // screen and the Menu's own rows no longer existed to click. This version
    // instead does ONE click from the Menu to land on the cards screen, then
    // does all further library-switching from there via the "Choose another
    // library" picker, matching how the real screen is actually used.
    //
    // Nothing about a specific library name or card number is hardcoded
    // anywhere below. Deliberately not cached or persisted anywhere either (no
    // store, no field saved to disk) -- the whole point of gating this behind a
    // PIN (see LibraryCardPinStore) is that a card number only ever gets read
    // off the real page while someone is actively looking at it, never kept
    // lying around afterward.

    /** Navigates to the account Menu screen, where every connected library's own
     *  "Manage Cards" button lives. */
    fun loadLibraryCardsPage() {
        requireWebView().loadUrl(LIBBY_MENU)
    }

    /**
     * Reads every connected library's own card number: clicks the first library
     * row's "Manage Cards" button on the Menu screen to land on the dedicated
     * cards screen, then uses that screen's own "Choose another library" picker
     * to cycle through every library, re-reading the digit-run card number
     * after each switch. See the section comment above for how each piece was
     * confirmed.
     */
    suspend fun scrapeLibraryCards(): List<LibraryCard> {
        val enterScript = """
            (() => {
                const row = document.querySelector('li.summary-list-library');
                const btn = row ? row.querySelector(
                    '.summary-list-library-card-button, [aria-label="Manage Cards"]'
                ) : null;
                if (!btn) return JSON.stringify({ found: false });
                btn.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        val entered = evaluateJs(enterScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        if (!entered) {
            Log.d(TAG, "scrapeLibraryCards: no library row found on the Menu screen")
            return emptyList()
        }

        val numberScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const spans = Array.from(document.querySelectorAll('[data-access="visual"]'));
                const match = spans.find((el) => /^\d{6,}${'$'}/.test(clean(el.textContent)));
                return JSON.stringify({ number: match ? clean(match.textContent) : '' });
            })();
        """.trimIndent()

        // Polls rather than a single fixed delay -- the navigation from the
        // Menu click above isn't instantaneous. Up to ~2.4s.
        var previousNumber = ""
        for (attempt in 0 until 8) {
            delay(300)
            previousNumber = evaluateJs(numberScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optString("number").orEmpty()
            if (previousNumber.isNotBlank()) break
        }

        val openPickerScript = """
            (() => {
                const chooser = document.querySelector('[aria-label="Choose another library."]');
                if (!chooser) return JSON.stringify({ found: false });
                const target = chooser.closest('button, a, [role="button"]') || chooser;
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        val rowsScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const rows = Array.from(document.querySelectorAll('li.summary-list-library[data-item-id]'));
                const libraries = rows
                    .map((row) => {
                        const nameNode = row.querySelector('.summary-list-library-name');
                        const hasTarget = !!row.querySelector('.summary-list-library-target');
                        return {
                            id: row.getAttribute('data-item-id'),
                            name: clean(nameNode ? nameNode.textContent : ''),
                            hasTarget,
                        };
                    })
                    .filter((item) => item.hasTarget && item.id && item.name);
                return JSON.stringify(libraries);
            })();
        """.trimIndent()

        suspend fun pickerRowCount(): Int =
            evaluateJs(rowsScript)?.let { runCatching { JSONArray(it) }.getOrNull() }?.length() ?: 0

        suspend fun openPicker(): Boolean {
            val opened = evaluateJs(openPickerScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optBoolean("found") == true
            if (!opened) return false
            for (attempt in 0 until 6) {
                delay(250)
                if (pickerRowCount() > 0) return true
            }
            return false
        }

        if (!openPicker()) {
            Log.d(TAG, "scrapeLibraryCards: could not open the \"Choose another library\" picker")
            return emptyList()
        }

        val libraries = evaluateJs(rowsScript)?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    item.getString("id") to item.getString("name")
                }
            }.getOrNull()
        }.orEmpty()

        val cards = mutableListOf<LibraryCard>()
        for ((libraryId, libraryName) in libraries) {
            // The picker may have closed itself after the previous pick (or
            // never opened yet, for the very first row) -- make sure it's open
            // before every click rather than assuming it stayed open.
            if (pickerRowCount() == 0 && !openPicker()) {
                Log.d(TAG, "scrapeLibraryCards: could not reopen the picker for \"$libraryName\"")
                continue
            }

            val escapedId = libraryId.replace("\\", "\\\\").replace("'", "\\'")
            val clickScript = """
                (() => {
                    const rows = Array.from(document.querySelectorAll('li.summary-list-library[data-item-id]'));
                    const row = rows.find((r) => r.getAttribute('data-item-id') === '$escapedId');
                    const btn = row ? row.querySelector('.summary-list-library-target') : null;
                    if (!btn) return JSON.stringify({ found: false });
                    btn.click();
                    return JSON.stringify({ found: true });
                })();
            """.trimIndent()
            val clicked = evaluateJs(clickScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optBoolean("found") == true
            if (!clicked) {
                Log.d(TAG, "scrapeLibraryCards: could not click the picker row for \"$libraryName\"")
                continue
            }

            // Polls for the digit span to show a NEW value rather than just any
            // value, since the previously-shown library's number can still be
            // sitting there for a moment right after the click.
            var cardNumber = ""
            for (attempt in 0 until 8) {
                delay(300)
                cardNumber = evaluateJs(numberScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.optString("number").orEmpty()
                if (cardNumber.isNotBlank() && cardNumber != previousNumber) break
            }
            if (cardNumber.isBlank()) {
                Log.d(TAG, "scrapeLibraryCards: no digit span found for \"$libraryName\" after switching")
                continue
            }
            previousNumber = cardNumber
            cards.add(LibraryCard(libraryName = libraryName, cardNumber = cardNumber))
        }

        Log.d(TAG, "scrapeLibraryCards: ${cards.size} of ${libraries.size} libraries yielded a card number")
        return cards
    }

    // --- Catalog search & borrow/hold ---------------------------------------------
    //
    // Libby's search results reuse the same `.title-tile` component family as the
    // shelf, so the scrapers below borrow the shelf scraper's technique (match by
    // class-embedded ids, read text out of the tile's own child elements). Confirmed
    // from a real captured search-results page: each tile has its own inline
    // "Borrow"/"Place Hold" button right there in the list (no separate details
    // overlay to open first), so [borrowOrHoldSearchResult] finds the specific tile
    // by titleId and clicks its own button directly, matching by exact visible
    // button TEXT rather than by CSS selector (mirroring how LibbyBridge's
    // player-frame script matches controls by aria-label instead of brittle class
    // names, since Libby doesn't expose stable class names for this either). Debug
    // builds already enable WebView debugging (see [init]), so if a step here stops
    // matching what Libby renders, `chrome://inspect` against the running app is the
    // fastest way to see the real markup and adjust the text patterns below.

    /**
     * Navigates the hidden WebView to [library]'s audiobook search results for [query].
     *
     * [page] defaults to the first page, but callers should page through further
     * results (2, 3, ...) rather than only ever requesting page 1: a title that's
     * heavily held/less immediately available can rank low enough in Libby's own
     * search ordering to land on a later page, and a caller that only ever looks at
     * page 1 will never see it at all -- even though it's fully findable by scrolling
     * to the next page in a real Libby search.
     *
     * Every search now needs to be run once per entry in [LIBBY_LIBRARIES] and
     * merged -- see the search screen's own loop over that list -- since a title
     * available (or ranked differently) at one linked library is otherwise
     * invisible if only ever searching a different one.
     */
    fun navigateToSearch(library: LibbyLibrary, query: String, page: Int = 1) {
        requireWebView().loadUrl(library.searchUrl(query, page))
    }

    /** Scrapes the currently-loaded search results page. Call after [navigateToSearch]
     *  once the page has had a moment to render (poll every few hundred ms, same
     *  pattern as the shelf hydration loop in PlayerDebugScreen). */
    fun scrapeSearchResults(onResult: (List<LibbySearchResult>) -> Unit) {
        val script = """
            (() => {
                const tiles = Array.from(document.querySelectorAll(
                    '.title-tile.data-title-tile-format_audiobook, .title-tile[class*="format_audiobook"]'
                ));

                const results = tiles.map((tile, index) => {
                    const classText = tile.className || '';
                    const titleIdMatch = classText.match(/data-title_(\d+)/);
                    const titleId = titleIdMatch ? titleIdMatch[1] : ('idx-' + index);
                    const title = (tile.querySelector('.title-tile-title')?.textContent || '').trim();
                    const author = (tile.querySelector('.title-tile-author')?.textContent || '').trim();
                    const cover = tile.querySelector('img[data-cover-id]')?.src || '';
                    const actionText = Array.from(tile.querySelectorAll('button, a'))
                        .map(el => (el.textContent || '').trim())
                        .filter(Boolean)
                        .join(' | ');
                    const lower = actionText.toLowerCase();
                    let availability = 'unknown';
                    // Checked before the "wait" branch on purpose: an expiring loan's
                    // tile shows BOTH "Open Audiobook" (you still have it) AND a
                    // "Place Hold" prompt (to re-borrow once it expires) at the same
                    // time -- confirmed on a real "Brigands & Breadknives" search
                    // result, which was misreported as "Wait list" before "open
                    // audiobook" and "on your loans shelf" were added here, even
                    // though the loan was already yours.
                    if (
                        lower.includes('open book') || lower.includes('open loan') ||
                        lower.includes('open audiobook') || lower.includes('already') ||
                        lower.includes('on your loans shelf')
                    ) {
                        availability = 'owned';
                    } else if (lower.includes('wait list') || lower.includes('place a hold') || lower.includes('hold')) {
                        availability = 'wait';
                    } else if (lower.includes('borrow')) {
                        availability = 'available';
                    }
                    return { titleId, title, author, coverUrl: cover, availability, availabilityText: actionText };
                }).filter(item => item.title);

                return JSON.stringify(results);
            })();
        """.trimIndent()

        requireWebView().evaluateJavascript(script) { raw ->
            try {
                val decoded = decodeJavascriptString(raw) ?: return@evaluateJavascript onResult(emptyList())
                val array = JSONArray(decoded)
                val items = buildList {
                    for (index in 0 until array.length()) {
                        val json = array.getJSONObject(index)
                        add(
                            LibbySearchResult(
                                titleId = json.optString("titleId"),
                                title = json.optString("title"),
                                author = json.optString("author"),
                                coverUrl = json.optString("coverUrl"),
                                availability = when (json.optString("availability")) {
                                    "available" -> LibbyAvailability.Available
                                    "wait" -> LibbyAvailability.Wait
                                    "owned" -> LibbyAvailability.Owned
                                    else -> LibbyAvailability.Unknown
                                },
                                availabilityText = json.optString("availabilityText"),
                            ),
                        )
                    }
                }
                Log.d(TAG, "scrapeSearchResults: ${items.size} results")
                onResult(items)
            } catch (_: Exception) {
                Log.e(TAG, "scrapeSearchResults failed")
                onResult(emptyList())
            }
        }
    }

    /** Runs [script] in the WebView and returns its decoded string result (or null). */
    private suspend fun evaluateJs(script: String): String? =
        kotlin.coroutines.suspendCoroutine { continuation ->
            requireWebView().evaluateJavascript(script) { raw ->
                continuation.resume(decodeJavascriptString(raw))
            }
        }

    /**
     * Best-effort genre/subject lookup for a single loan, used by the Listening Stats
     * feature (see GenreStore). Confirmed via a real captured "Title Details" page that
     * Libby's shelf/player screens themselves never show genre -- it only exists on this
     * third page type, which this app has never had a reason to navigate to before now.
     *
     * CALLERS MUST ONLY invoke this when the shared hidden WebView is confirmed idle --
     * parked on the shelf, nothing playing, no search/borrow in progress (see the
     * idle-detection gate around this call in PlayerDebugScreen's polling loop). This
     * function does not check that itself; it navigates immediately and unconditionally
     * when called, since by design it's meant to be called at most once per book, ever
     * (GenreStore.hasAttempted gates repeat calls) -- see that store's cache-forever
     * policy for why paying this navigation cost twice for the same book is avoided.
     *
     * Always returns the WebView to the shelf before returning, whether or not a
     * genre was actually found, so the caller's own shelf-state assumptions
     * (currentUrl.contains("/shelf")) hold again immediately afterward.
     */
    suspend fun fetchLoanGenres(titleId: String): List<String> {
        val detailsUrl = "https://libbyapp.com/shelf/similar-$titleId/page-1/$titleId"
        requireWebView().loadUrl(detailsUrl)

        // Ready-check deliberately waits for the actual genre LINKS, not just the
        // wrapping ".screen-title-details-subjects" section -- a real device test
        // showed that section can exist in the DOM a beat before Libby finishes
        // rendering the clickable subject buttons inside it. Checking only for the
        // container let this poll loop break out early and scrape zero links even
        // though the page had genuinely loaded, which is why every book came back
        // "Unknown" despite the page-load itself working fine.
        val checkReadyScript = """
            (() => JSON.stringify({
                onDetails: location.href.includes('/shelf/similar-'),
                genreLinkCount: document.querySelectorAll(
                    '.screen-title-details-subjects .filter-cloud-base a.filter-button[href*="/shelf/subject-"]'
                ).length
            }))();
        """.trimIndent()
        var onDetails = false
        var sawGenreLinks = false
        for (attempt in 0 until 15) {
            delay(400)
            val json = evaluateJs(checkReadyScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json?.optBoolean("onDetails") == true) {
                onDetails = true
                if (json.optInt("genreLinkCount", 0) > 0) {
                    sawGenreLinks = true
                    break
                }
            }
        }

        val genres = if (sawGenreLinks) {
            val scrapeScript = """
                (() => {
                    const container = document.querySelector(
                        '.screen-title-details-subjects .filter-cloud-base'
                    );
                    if (!container) return JSON.stringify([]);
                    const links = Array.from(
                        container.querySelectorAll('a.filter-button[href*="/shelf/subject-"]')
                    );
                    const genres = links.map(a => (a.textContent || '').trim()).filter(Boolean);
                    return JSON.stringify(genres);
                })();
            """.trimIndent()
            evaluateJs(scrapeScript)?.let { raw ->
                runCatching {
                    val array = JSONArray(raw)
                    (0 until array.length()).map(array::getString)
                }.getOrDefault(emptyList())
            }.orEmpty()
        } else if (onDetails) {
            Log.d(TAG, "fetchLoanGenres: page loaded but no genre links appeared within budget for $titleId")
            emptyList()
        } else {
            Log.d(TAG, "fetchLoanGenres: title-details page never loaded for $titleId")
            emptyList()
        }

        loadLibraryPage()
        return genres
    }

    /**
     * Finds the tile matching [titleId] in the currently-rendered search results, clicks
     * its own "Borrow" or "Place Hold" button, then drives Libby's real confirmation
     * screen through to completion. Returns (confirmed, detail) where [detail] is the
     * matched action's visible text ("Borrow" / "Place Hold") on success, or a short
     * human-readable reason on failure.
     *
     * This is a genuine THREE-step flow, confirmed end-to-end with `chrome://inspect`
     * against a real device:
     *
     * 1. Each search-result tile has its own inline Borrow/Place Hold button right there
     *    in the list (alongside "Play Sample" and "Save") -- there's no separate detail
     *    page to open first. Clicking it is scoped to this specific tile (matched by
     *    titleId) rather than matched anywhere on the page, since the whole results list
     *    stays rendered and a page-wide match could just as easily hit a DIFFERENT
     *    result's button.
     * 2. That click only navigates Libby's SPA to a `/request?key=...` REVIEW screen
     *    (book details, due date, loan/hold counts) -- it does NOT place the loan by
     *    itself. A captured trace confirmed this screen sits there unconfirmed
     *    indefinitely with nothing auto-advancing it, which is exactly why borrows
     *    silently never landed on the shelf when Livrospeak treated step 1 as "done".
     * 3. That review screen has its OWN separate confirm button
     *    (`.circ-confirm-button`, visible text "BORROW") which must be clicked to
     *    actually finalize the loan/hold. On success the URL and document.title do NOT
     *    change -- Libby swaps the screen's content in place to "Borrowed until <date>."
     *    plus "Open Audiobook / Keep Browsing / Go To Shelf", which is what this function
     *    polls for to confirm the action really completed (rather than just reporting
     *    success once the confirm button was found and clicked).
     */
    suspend fun borrowOrHoldSearchResult(titleId: String): Pair<Boolean, String> {
        val clickTileScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const tiles = Array.from(document.querySelectorAll('.title-tile'));
                const tile = tiles.find(t => (t.className || '').includes('data-title_$titleId'));
                if (!tile) return JSON.stringify({ found: false, actionText: '' });
                const buttons = Array.from(tile.querySelectorAll('button, a'));
                const target = buttons.find((el) => {
                    const text = clean(el.textContent).toLowerCase();
                    return text === 'borrow' || text === 'place hold' || text === 'place a hold';
                });
                if (!target) return JSON.stringify({ found: false, actionText: '' });
                const actionText = clean(target.textContent);
                target.click();
                return JSON.stringify({ found: true, actionText });
            })();
        """.trimIndent()

        // Retried rather than a single attempt: a real report showed a title that was
        // JUST returned still rendering its OLD "Open Audiobook" tile for a brief
        // window afterward (no Borrow/Place-Hold button yet to find), even though the
        // real libbyapp.com site already reflected the return -- this is the same kind
        // of transient-state window scrapeSearchResults' own doc comment already calls
        // out for an expiring loan's tile. Polling for a few seconds rides out that
        // window instead of failing on the very first stale read. Combined with the
        // LOAD_NO_CACHE change on this WebView's settings (see init()), which rules
        // out a stale cached page being the cause.
        var tileClick: JSONObject? = null
        for (attempt in 0 until 8) {
            tileClick = evaluateJs(clickTileScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (tileClick?.optBoolean("found") == true) break
            delay(500)
        }
        var actionText = tileClick?.optString("actionText").orEmpty()
        if (tileClick?.optBoolean("found") != true) {
            return false to "Couldn't find a Borrow or Place a Hold button"
        }

        // Step 2: wait for Libby's router to land on the /request review screen.
        val onRequestCheckScript =
            "(() => JSON.stringify({ onRequest: location.href.includes('/request') }))();"
        var onRequestScreen = false
        for (attempt in 0 until 15) {
            delay(400)
            val onRequest = evaluateJs(onRequestCheckScript)
                ?.let { runCatching { JSONObject(it).optBoolean("onRequest") }.getOrNull() } ?: false
            if (onRequest) {
                onRequestScreen = true
                break
            }
        }
        if (!onRequestScreen) {
            return false to "Never reached Libby's confirmation screen"
        }

        // Step 3: click the review screen's OWN confirm button (distinct from the tile
        // button clicked in step 1 above).
        val confirmClickScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const btn = document.querySelector('.circ-confirm-button') ||
                    Array.from(document.querySelectorAll('button')).find((el) => {
                        const text = clean(el.textContent).toLowerCase();
                        return text === 'borrow' || text === 'place hold' || text === 'place a hold';
                    });
                if (!btn) return JSON.stringify({ found: false, actionText: '' });
                const clickedText = clean(btn.textContent);
                btn.click();
                return JSON.stringify({ found: true, actionText: clickedText });
            })();
        """.trimIndent()
        var confirmClicked = false
        for (attempt in 0 until 10) {
            delay(300)
            val json = evaluateJs(confirmClickScript)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json?.optBoolean("found") == true) {
                actionText = json.optString("actionText").ifBlank { actionText }
                confirmClicked = true
                break
            }
        }
        if (!confirmClicked) {
            return false to "Couldn't find Libby's own confirm button"
        }

        // Step 4: wait for Libby to actually finish placing the loan/hold. Confirmed via
        // chrome://inspect: on success the URL/title never change, only the screen's
        // content is replaced in place.
        val successCheckScript = """
            (() => JSON.stringify({
                success: /borrowed until|go to shelf/i.test(document.body.innerText)
            }))();
        """.trimIndent()
        var succeeded = false
        for (attempt in 0 until 20) {
            delay(400)
            val success = evaluateJs(successCheckScript)
                ?.let { runCatching { JSONObject(it).optBoolean("success") }.getOrNull() } ?: false
            if (success) {
                succeeded = true
                break
            }
        }
        return if (succeeded) {
            true to actionText
        } else {
            false to "Libby never confirmed the $actionText completed"
        }
    }

    /** Recovers a loan's cardId/titleId from the loanUrl shape [scrapeAudiobookLoans]
     *  already builds ("https://libbyapp.com/open/loan/{cardId}/{titleId}"), so
     *  returning a loan needs no new fields threaded through LoanItem/Audiobook. */
    fun loanIdsFromUrl(loanUrl: String): Pair<String, String>? {
        val match = Regex("""/open/loan/(\d+)/(\d+)""").find(loanUrl) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /**
     * Returns an active Libby loan early. A first version of this hard-navigated straight
     * to the return URL via loadUrl() -- confirmed broken on a real device, most likely
     * because that reloads Libby's single-page app from scratch on that exact route
     * instead of letting its own router handle the transition, unlike the proven
     * [borrowOrHoldSearchResult] flow, which clicks a real in-app element. This clicks the
     * loan tile's own return link on the shelf instead, the same way a real tap would.
     *
     * Libby's own help docs (help.libbyapp.com) describe the real flow as tapping "Manage
     * Loan" on a loan, then "Return Early" -- not any of the guessed labels tried before.
     * Still no real captured trace of that confirmation screen, so several plausible
     * labels are tried, and every match on every poll is clicked rather than stopping at
     * the first one, in case Libby needs more than one confirming tap. Either way, success
     * is decided by re-scraping the shelf afterward -- the one scraper already proven
     * reliable in production -- and checking the loan is actually gone, rather than by
     * trusting any of those guessed labels actually matched something.
     */
    suspend fun returnLoan(cardId: String, titleId: String): Boolean {
        if (!requireWebView().url.orEmpty().contains("/shelf")) {
            loadLibraryPage()
            delay(1200)
        }
        scrollToBottom()
        delay(400)

        val clickReturnLinkScript = """
            (() => {
                const tiles = Array.from(document.querySelectorAll('.title-tile'));
                const tile = tiles.find(t => (t.className || '').includes('data-title_$titleId'));
                if (!tile) return JSON.stringify({ found: false });
                const link = tile.querySelector('a[href*="/shelf/loans/"][href*="/return"]');
                if (!link) return JSON.stringify({ found: false });
                link.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        val clickedTileLink = evaluateJs(clickReturnLinkScript)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        if (!clickedTileLink) return false

        val clickConfirmScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const target = Array.from(document.querySelectorAll('button, a')).find((el) => {
                    const text = clean(el.textContent).toLowerCase();
                    return text === 'return early' || text === 'return loan' ||
                        text === 'return title' || text === 'return book' ||
                        text === 'return' || text === 'yes, return' || text === 'confirm' ||
                        text === 'yes';
                });
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        repeat(10) {
            delay(500)
            evaluateJs(clickConfirmScript)
        }

        delay(800)
        loadLibraryPage()
        delay(800)
        scrollToBottom()
        delay(400)

        val targetLoanUrl = "https://libbyapp.com/open/loan/$cardId/$titleId"
        return kotlin.coroutines.suspendCoroutine { continuation ->
            scrapeAudiobookLoans { loans ->
                continuation.resume(loans.none { it.loanUrl == targetLoanUrl })
            }
        }
    }

    /**
     * Renews an active Libby loan for another lending period. No real captured trace of
     * this flow exists yet -- unlike return, which at least had a confirmed href to click
     * before this was written. Libby's own help docs (help.libbyapp.com) describe the real
     * flow as tapping "Manage Loan" on a loan, then "Renew Loan", optionally choosing a
     * different loan period next to "Borrowing for...", then tapping "Renew Loan" again to
     * confirm. This tries a direct renew link on the tile first (mirroring the pattern that
     * worked for return), falling back to that documented "Manage Loan" control, then
     * clicks through as many "Renew Loan" prompts as appear.
     *
     * A renewed loan stays on the shelf (unlike a returned one, which disappears), so
     * success can't be judged by whether the loan is still there. It's judged by comparing
     * the loan's own due-date text before and after -- if it didn't change, nothing was
     * actually renewed, whatever any button along the way claimed.
     */
    suspend fun renewLoan(cardId: String, titleId: String): Boolean {
        if (!requireWebView().url.orEmpty().contains("/shelf")) {
            loadLibraryPage()
            delay(1200)
        }
        scrollToBottom()
        delay(400)

        val targetLoanUrl = "https://libbyapp.com/open/loan/$cardId/$titleId"
        val dueBefore = kotlin.coroutines.suspendCoroutine<String> { continuation ->
            scrapeAudiobookLoans { loans ->
                continuation.resume(loans.firstOrNull { it.loanUrl == targetLoanUrl }?.dueText.orEmpty())
            }
        }

        val clickRenewEntryScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const tiles = Array.from(document.querySelectorAll('.title-tile'));
                const tile = tiles.find(t => (t.className || '').includes('data-title_$titleId'));
                if (!tile) return JSON.stringify({ found: false });

                const directLink = tile.querySelector('a[href*="/shelf/loans/"][href*="/renew"]');
                if (directLink) {
                    directLink.click();
                    return JSON.stringify({ found: true });
                }

                const manageControl = Array.from(tile.querySelectorAll('button, a')).find((el) => {
                    return clean(el.textContent).toLowerCase() === 'manage loan';
                });
                if (manageControl) {
                    manageControl.click();
                    return JSON.stringify({ found: true });
                }
                return JSON.stringify({ found: false });
            })();
        """.trimIndent()
        val enteredFlow = evaluateJs(clickRenewEntryScript)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("found") == true
        if (!enteredFlow) return false

        // Whether that landed directly on a renew-confirmation screen or on a "Manage
        // Loan" panel that still needs its own "Renew Loan" tap (possibly twice, per the
        // documented flow), every poll clicks whatever currently matches rather than
        // stopping at the first hit.
        val clickRenewLoanScript = """
            (() => {
                const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
                const target = Array.from(document.querySelectorAll('button, a')).find((el) => {
                    const text = clean(el.textContent).toLowerCase();
                    return text === 'renew loan' || text === 'renew' || text === 'confirm' ||
                        text === 'yes';
                });
                if (!target) return JSON.stringify({ found: false });
                target.click();
                return JSON.stringify({ found: true });
            })();
        """.trimIndent()
        repeat(10) {
            delay(500)
            evaluateJs(clickRenewLoanScript)
        }

        delay(800)
        loadLibraryPage()
        delay(800)
        scrollToBottom()
        delay(400)

        val dueAfter = kotlin.coroutines.suspendCoroutine<String> { continuation ->
            scrapeAudiobookLoans { loans ->
                continuation.resume(loans.firstOrNull { it.loanUrl == targetLoanUrl }?.dueText.orEmpty())
            }
        }
        return dueAfter.isNotBlank() && dueAfter != dueBefore
    }

    /** Basic Step-1 bridge diagnostic for Logcat. */
    fun ping(onResult: (String?) -> Unit) {
        val script = """
            (() => JSON.stringify({
                title: document.title,
                url: window.location.href,
                readyState: document.readyState
            }))();
        """.trimIndent()
        requireWebView().evaluateJavascript(script) { raw ->
            onResult(decodeJavascriptString(raw))
        }
    }

    /**
     * Libby does not expose a single stable login cookie. For this prototype,
     * reaching a normal Libby app route after initial setup counts as an
     * authenticated/initialized session. The page remains hidden behind Livrospeak's
     * native UI during connection and playback.
     */
    fun checkIsLoggedIn(onResult: (Boolean) -> Unit) {
        val script = """
            (() => JSON.stringify({
                url: window.location.href,
                title: document.title,
                ready: document.documentElement.classList.contains('data-client_ready'),
                realm: Array.from(document.documentElement.classList)
                    .some(c => c === 'data-realm_shelf' || c === 'data-realm_open')
            }))();
        """.trimIndent()

        requireWebView().evaluateJavascript(script) { raw ->
            try {
                val decoded = decodeJavascriptString(raw) ?: return@evaluateJavascript onResult(false)
                val json = JSONObject(decoded)
                val url = json.optString("url")
                val initialized = json.optBoolean("ready") &&
                    (json.optBoolean("realm") || url.contains("/shelf") || url.contains("/open/loan/"))
                Log.d(TAG, "checkIsLoggedIn: route=${safeRouteName(url)} initialized=$initialized")
                onResult(initialized)
            } catch (_: Exception) {
                Log.e(TAG, "checkIsLoggedIn failed")
                onResult(false)
            }
        }
    }

    suspend fun checkIsLoggedInSuspend(): Boolean =
        kotlin.coroutines.suspendCoroutine { continuation ->
            checkIsLoggedIn { result -> continuation.resume(result) }
        }

    private fun decodeJavascriptString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try {
            // evaluateJavascript returns a JSON-encoded JavaScript string.
            org.json.JSONTokener(raw).nextValue() as? String
        } catch (_: Exception) {
            raw.removeSurrounding("\"").replace("\\\"", "\"")
        }
    }

    private fun safeRouteName(url: String): String = when {
        url.contains("/open/loan/") -> "open-loan"
        url.contains("/shelf") -> "shelf"
        url == LIBBY_ROOT || url.substringBefore('?') == LIBBY_ROOT -> "root"
        else -> "libby-app"
    }
}
