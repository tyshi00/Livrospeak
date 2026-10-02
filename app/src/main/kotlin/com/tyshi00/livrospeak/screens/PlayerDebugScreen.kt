package com.tyshi00.livrospeak.screens

import android.util.Log
import android.app.Activity
import android.provider.MediaStore
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.tyshi00.livrospeak.LibbyBridge
import com.tyshi00.livrospeak.PlaybackForegroundService
import com.tyshi00.livrospeak.LibbyWebPlayer
import com.tyshi00.livrospeak.library.Audiobook
import com.tyshi00.livrospeak.library.AudiobookProgressStore
import com.tyshi00.livrospeak.library.AudiobookSource
import com.tyshi00.livrospeak.library.GenreStore
import com.tyshi00.livrospeak.library.LivrospeakSettings
import com.tyshi00.livrospeak.library.LivrospeakSettingsStore
import com.tyshi00.livrospeak.library.LibbyAvailability
import com.tyshi00.livrospeak.library.LibbySearchResult
import com.tyshi00.livrospeak.library.LibraryCardPinStore
import com.tyshi00.livrospeak.library.ListeningSession
import com.tyshi00.livrospeak.library.ListeningSessionStore
import com.tyshi00.livrospeak.library.LocalBookRepository
import com.tyshi00.livrospeak.library.LocalScanResult
import com.tyshi00.livrospeak.library.HoldItem
import com.tyshi00.livrospeak.library.LibbyTag
import com.tyshi00.livrospeak.library.displayName
import com.tyshi00.livrospeak.library.LoanItem
import com.tyshi00.livrospeak.library.TaggedTitle
import com.tyshi00.livrospeak.library.PersistedActiveAudiobook
import com.tyshi00.livrospeak.library.RssFeed
import com.tyshi00.livrospeak.library.RssDownloadManager
import com.tyshi00.livrospeak.library.RssDownloadStatus
import com.tyshi00.livrospeak.library.RssFeedRepository
import com.tyshi00.livrospeak.library.RssFeedResult
import com.tyshi00.livrospeak.libby.LibbyConnectCoordinator
import com.tyshi00.livrospeak.libby.LibbyConnectState
import com.tyshi00.livrospeak.libby.LibbySessionState
import com.tyshi00.livrospeak.player.PlayerState
import com.tyshi00.livrospeak.player.PlayerReadiness
import com.tyshi00.livrospeak.player.LocalPlaybackController
import com.tyshi00.livrospeak.ui.LightBarButton
import com.tyshi00.livrospeak.ui.LightBarButtonDefaults
import com.tyshi00.livrospeak.ui.LightBottomBar
import com.tyshi00.livrospeak.ui.LightIcon
import com.tyshi00.livrospeak.ui.LightIcons
import com.tyshi00.livrospeak.ui.LightUrlInputEditor
import com.tyshi00.livrospeak.ui.LightLazyScrollView
import com.tyshi00.livrospeak.ui.LightScrollView
import com.tyshi00.livrospeak.ui.LightText
import com.tyshi00.livrospeak.ui.LightTextVariant
import com.tyshi00.livrospeak.ui.LightTheme
import com.tyshi00.livrospeak.ui.LightThemeController
import com.tyshi00.livrospeak.ui.LightThemeTokens
import com.tyshi00.livrospeak.ui.LightTopBar
import com.tyshi00.livrospeak.ui.LightTopBarCenter
import com.tyshi00.livrospeak.ui.gridUnitsAsDp
import com.tyshi00.livrospeak.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private const val TAG = "LibbyLight"

// How many pages of Libby search results to fetch and merge per search. Libby's
// own search ranking can push a less-immediately-available title (e.g. heavily
// held) past page 1, and the old code only ever requested page 1 -- so a book
// that's genuinely in the catalog and findable by paging through a real Libby
// search could be completely invisible to Livrospeak's search. 5 is a balance between
// coverage and not making every search take forever; raise it if books are still
// missing, at the cost of a slower worst-case search.
private const val MAX_LIBBY_SEARCH_PAGES = 5

private enum class LibbyLibraryReadiness {
    NotStarted,
    Connecting,
    Hydrating,
    Ready,
    Failed,
    Disconnected,
}

/**
 * RSS feed source is temporarily disabled per product direction -- flip this back to
 * true to fully restore it. Every bit of RSS logic (RssFeedRepository,
 * RssDownloadManager, GenreStore's RSS paths, the RssFeeds, AddRssFeed, and
 * RssFeedDetail screens) is left completely intact and untouched; this single flag
 * just makes the feature unreachable and stops its background network refresh.
 */
private const val RSS_FEED_FEATURE_ENABLED = false

@Composable
fun PlayerDebugScreen() {
    val persistedActiveAtStartup = remember { AudiobookProgressStore.lastActiveAudiobook() }
    val persistedActiveRecord by AudiobookProgressStore.activeAudiobook.collectAsState()
    var state by remember {
        mutableStateOf(
            persistedActiveAtStartup
                ?.takeIf { it.source == AudiobookSource.Libby }
                ?.toRememberedPlayerState()
                ?: PlayerState(),
        )
    }
    val localPlayerState by LocalPlaybackController.state.collectAsState()
    val scope = rememberCoroutineScope()
    var currentUrl by remember { mutableStateOf("") }
    val connectState by LibbyConnectCoordinator.connectState.collectAsState()
    val libbySessionState by LibbyConnectCoordinator.sessionState.collectAsState()
    val libbyRendererFailed by LibbyWebPlayer.rendererFailed.collectAsState()
    val libbyPlaybackHostFailed by PlaybackForegroundService.startFailed.collectAsState()
    val sessionReady = libbySessionState == LibbySessionState.Connected
    val currentSessionReady by rememberUpdatedState(sessionReady)
    var showBooks by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showAudiobookSources by remember { mutableStateOf(false) }
    var showLibbySource by remember { mutableStateOf(false) }
    var showLibbyConnect by remember { mutableStateOf(false) }
    var showDisconnectConfirmation by remember { mutableStateOf(false) }
    var showResetLibraryCardPinConfirmation by remember { mutableStateOf(false) }
    var showLocalBooks by remember { mutableStateOf(false) }
    var showRssFeeds by remember { mutableStateOf(false) }
    var showAddRssFeed by remember { mutableStateOf(false) }
    var selectedRssFeedId by remember { mutableStateOf<String?>(null) }
    var rssFeedMessage by remember { mutableStateOf("") }
    var showDownloads by remember { mutableStateOf(false) }
    var downloadsEditMode by remember { mutableStateOf(false) }
    var pendingDownloadRemoval by remember { mutableStateOf<Audiobook?>(null) }
    var downloadRemovalMessage by remember { mutableStateOf("") }
    var showManageLoan by remember { mutableStateOf(false) }
    var manageLoanBusyAction by remember { mutableStateOf<LoanAction?>(null) }
    var manageLoanMessage by remember { mutableStateOf("") }
    var showLibbySearch by remember { mutableStateOf(false) }
    var showListeningStats by remember { mutableStateOf(false) }
    var showLibbyTools by remember { mutableStateOf(false) }
    // Shared placeholder for any future Libby Tools row that isn't built yet --
    // Holds, Tags, and now Library Cards all have real screens, so nothing uses
    // this today, but it's kept rather than removed in case a future row needs it.
    var libbyToolsPlaceholderMessage by remember { mutableStateOf<String?>(null) }
    var showLibraryCards by remember { mutableStateOf(false) }
    var showHolds by remember { mutableStateOf(false) }
    var holds by remember { mutableStateOf<List<HoldItem>>(emptyList()) }
    var holdsLoading by remember { mutableStateOf(false) }
    var holdsMessage by remember { mutableStateOf("") }
    var activeHold by remember { mutableStateOf<HoldItem?>(null) }
    var manageHoldBusyAction by remember { mutableStateOf<HoldAction?>(null) }
    var manageHoldMessage by remember { mutableStateOf("") }
    var showTags by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf<List<LibbyTag>>(emptyList()) }
    var tagsLoading by remember { mutableStateOf(false) }
    var tagsMessage by remember { mutableStateOf("") }
    var activeTag by remember { mutableStateOf<LibbyTag?>(null) }
    var tagTitles by remember { mutableStateOf<List<TaggedTitle>>(emptyList()) }
    var tagTitlesLoading by remember { mutableStateOf(false) }
    var tagTitlesMessage by remember { mutableStateOf("") }
    // Rename/delete overlay on top of activeTag's own TagDetailScreen, same nesting
    // idea as manageTagsCreatingNew over ManageTitleTagsScreen -- Back returns to
    // the tag's own detail view rather than all the way out.
    var renamingTag by remember { mutableStateOf(false) }
    var renameTagName by remember { mutableStateOf("") }
    var renameTagMessage by remember { mutableStateOf("") }
    var renameTagSubmitting by remember { mutableStateOf(false) }
    // Fresh per "Rename" tap (see RenameTagScreen's own doc comment) -- same
    // keyboard-cache-key-freshness fix NewTagScreen already needed.
    var renameTagKeyboardSession by remember { mutableStateOf(0L) }
    var confirmingDeleteTag by remember { mutableStateOf(false) }
    var deleteTagMessage by remember { mutableStateOf("") }
    var deleteTagSubmitting by remember { mutableStateOf(false) }
    // Reachable from either ManageLoanScreen (an owned title) or the Libby search
    // confirm screen (a not-yet-borrowed title) -- stored as (titleId, titleName)
    // rather than nested under either screen's own state, so Back always returns to
    // whichever one opened it instead of needing two separate copies of this flow.
    var manageTagsForTitle by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Re-confirms the hidden WebView is actually on the page this title's tile
    // lives on -- set once when ManageTitleTagsScreen opens (see openManageTags),
    // then re-run before EVERY toggle/create action, not just the initial open.
    // The initial-open-only version of this (ensureOnShelf for loans, the
    // re-navigate step in LibbySearchScreen's requestManageTags for search
    // results) only covered the first scrape; real time passes while someone
    // reads existing tags, taps "New Tag," and types a name, which is enough for
    // the shared WebView to drift again -- confirmed via a real "no-button"
    // failure recurring specifically on tag CREATION after the initial open had
    // already succeeded. Null for a context with nothing to re-sync (shouldn't
    // normally happen, but toggle/create just skip the step rather than fail).
    var manageTagsResync by remember { mutableStateOf<(suspend () -> Boolean)?>(null) }
    // The tag catalog as seen from THIS title's own Save dialog -- deliberately
    // separate from the global `tags` list (which backs the plain Saved Tags screen
    // and carries real ids + title counts from the Tags list page). See
    // LibbyWebPlayer.scrapeSaveDialogTags's doc comment for why this flow stopped
    // reusing that global list: fetching it used to navigate the hidden WebView away
    // from this title's tile and never back, breaking every later toggle/create.
    var manageTagsCatalog by remember { mutableStateOf<List<LibbyTag>>(emptyList()) }
    var manageTagsMembership by remember { mutableStateOf<Set<String>>(emptySet()) }
    var manageTagsLoading by remember { mutableStateOf(false) }
    var manageTagsBusyTagId by remember { mutableStateOf<String?>(null) }
    var manageTagsMessage by remember { mutableStateOf("") }
    // The "+ New Tag" sub-flow from within ManageTitleTagsScreen -- the only real
    // entry point for tag creation, since Libby only lets you create a tag while
    // tagging a specific title (see LibbyWebPlayer's Tags section comment). Kept as
    // its own overlay flag (checked before manageTagsForTitle's own branch) rather
    // than clearing manageTagsForTitle, so Back from here returns to that title's
    // tag list rather than all the way out.
    var manageTagsCreatingNew by remember { mutableStateOf(false) }
    var manageTagsNewName by remember { mutableStateOf("") }
    var manageTagsNewMessage by remember { mutableStateOf("") }
    var manageTagsNewSubmitting by remember { mutableStateOf(false) }
    // Forces LightUrlInputEditor's keyboard cache (keyed only by the string passed
    // in as keyboardKey -- see that file's own doc comment) to hand back a FRESH
    // EnQwertyLp3KeyboardViewModel every time the "+ New Tag" flow is entered,
    // instead of the one hardcoded key ("LivrospeakNewTagKeyboard") reusing the
    // SAME cached instance -- and its internal letters/symbols layout mode, which
    // nothing in this file reads or resets -- across every New Tag session for the
    // rest of the app's process lifetime. Confirmed as the cause of a real report:
    // the keyboard opened in numeric/symbol mode for an unrelated title's new tag
    // because it was last left there typing "?" into a tag named "Read?". Only
    // regenerated when "+ New Tag" is freshly tapped (see onNewTag below), not on
    // every recomposition, so mid-typing state within one session is unaffected.
    var manageTagsNewKeyboardSession by remember { mutableStateOf(0L) }

    fun openManageTags(titleId: String, titleName: String, resync: (suspend () -> Boolean)? = null) {
        manageTagsForTitle = titleId to titleName
        manageTagsResync = resync
        manageTagsCatalog = emptyList()
        manageTagsMembership = emptySet()
        manageTagsBusyTagId = null
        manageTagsLoading = true
        manageTagsMessage = ""
        scope.launch {
            // Confirmed via a real device test (the "no-tile" diagnostic
            // openSavePanelForTitle now surfaces): for an owned loan, the shared
            // hidden WebView is essentially never still on the shelf by the time
            // this runs -- opening the book to reach ManageLoanScreen in the first
            // place already navigated it straight to that loan's own player page
            // (see openBook's eager LibbyWebPlayer.openLoan call above), and that
            // page has no `.title-tile` elements on it at all. [resync] is how the
            // caller re-establishes the right page -- re-navigate to the shelf for
            // an owned loan (see ManageLoanScreen's wiring below), or re-navigate to
            // the exact search page for a not-yet-borrowed title (see
            // LibbySearchScreen's requestManageTags) -- and it's re-run before every
            // later toggle/create too, not just this first open (see
            // manageTagsResync's own doc comment for why the first open alone
            // wasn't enough).
            resync?.invoke()
            // Catalog and membership come from this title's own Save dialog in one
            // pass -- never from the separate Tags list page, which would navigate
            // the hidden WebView away from this title's tile (see
            // scrapeSaveDialogTags's doc comment).
            val (catalog, membership) = LibbyWebPlayer.scrapeSaveDialogTags(titleId)
            manageTagsCatalog = catalog
            manageTagsMembership = membership
            manageTagsLoading = false
        }
    }

    fun refreshHolds() {
        holdsLoading = true
        holdsMessage = ""
        scope.launch {
            LibbyWebPlayer.loadHoldsPage()
            delay(1000)
            LibbyWebPlayer.scrollToBottom()
            delay(400)
            LibbyWebPlayer.scrapeHolds { found ->
                holds = found
                holdsLoading = false
                if (found.isEmpty()) holdsMessage = "No holds."
            }
        }
    }

    suspend fun scrapeTagsSuspend(): List<LibbyTag> = suspendCancellableCoroutine { continuation ->
        LibbyWebPlayer.scrapeTags { found ->
            if (continuation.isActive) continuation.resume(found)
        }
    }

    fun refreshTags() {
        tagsLoading = true
        tagsMessage = ""
        scope.launch {
            LibbyWebPlayer.loadTagsPage()
            delay(1000)
            // Sorts oldest-first first so newly created tags consistently land
            // at the end of the list instead of wherever the page's default
            // order happens to put them -- see sortTagsByOldest's own doc
            // comment. Best-effort/silent: if it can't, scrapeTags below still
            // runs in whatever order the page already had.
            LibbyWebPlayer.sortTagsByOldest()
            var found = scrapeTagsSuspend()
            // The tag the sort above just moved (almost always the newest one,
            // since the sort is oldest-first) can render with an empty count badge
            // for a beat right after the list re-settles -- confirmed via a real
            // report that a brand-new tag showed no title count in Saved Tags while
            // every other tag's count rendered fine, even though the real Libby
            // page shows it correctly once things settle (same `.tag-plank-count`
            // markup either way -- this isn't a wrong selector, just an early read).
            // Re-scraping once more after a short pause catches that case instead
            // of trusting the very first read right after the sort action.
            if (found.any { it.titleCount == 0 }) {
                delay(800)
                val resettled = scrapeTagsSuspend()
                if (resettled.isNotEmpty()) found = resettled
            }
            tags = found
            tagsLoading = false
            if (found.isEmpty()) tagsMessage = "No tags yet."
        }
    }

    fun openTag(tag: LibbyTag) {
        activeTag = tag
        tagTitles = emptyList()
        tagTitlesLoading = true
        tagTitlesMessage = ""
        scope.launch {
            // tag.id is the real Libby URL segment captured by scrapeTags, so this
            // tag's detail page is loaded directly rather than clicked through.
            LibbyWebPlayer.loadTagPage(tag.id)
            delay(1000)
            // A single scroll-to-bottom isn't enough for a long tag -- confirmed via
            // real device testing that a smart tag with 18 titles only rendered 3
            // until scrolled further: each scroll reveals more lazily-loaded titles,
            // which grows the page height and needs another scroll to reach the new
            // bottom, same idea as the repeated-scroll loops elsewhere in this file.
            // 6 passes is a generous margin over what a real 18-title tag needed.
            repeat(6) {
                LibbyWebPlayer.scrollToBottom()
                delay(400)
            }
            LibbyWebPlayer.scrapeTitlesForTag { found ->
                // This scrape now takes 1000ms + 6*400ms = 3.4s+ end to end, which is
                // long enough that a user can tap a different tag (or back out
                // entirely) before it resolves. Without this guard, that OLD tag's
                // late result would overwrite whatever's now showing for the NEW
                // tag -- including stomping a just-loaded real list with an empty
                // one, which is what produced the intermittent "No titles in this
                // tag yet" reported for tags that do have titles. activeTag?.id is
                // read fresh here (not captured from the outer `tag` above) so this
                // catches every case: switching to a different tag, or backing out
                // of the Tags flow entirely (activeTag becomes null).
                if (activeTag?.id != tag.id) return@scrapeTitlesForTag
                tagTitles = found
                tagTitlesLoading = false
                if (found.isEmpty()) tagTitlesMessage = "No titles in this tag yet."
            }
        }
    }
    // Guards the idle-only Libby genre lookup in the polling loop below so at most one
    // is ever in flight -- fetchLoanGenres takes up to a few seconds and navigates the
    // shared hidden WebView, so overlapping calls would fight over it.
    var genreFetchInProgress by remember { mutableStateOf(false) }
    // Diagnostic only -- see the edge-triggered "Genre idle gate" log below.
    var lastGenreDebugSnapshot by remember { mutableStateOf("") }
    val livrospeakSettings by LivrospeakSettingsStore.settings.collectAsState()

    var loans by remember { mutableStateOf<List<LoanItem>>(emptyList()) }
    val localBooks by LocalBookRepository.books.collectAsState()
    val localScanResult by LocalBookRepository.scanResult.collectAsState()
    val localScanning by LocalBookRepository.scanning.collectAsState()
    val rssFeeds by RssFeedRepository.feeds.collectAsState()
    val rssReady by RssFeedRepository.ready.collectAsState()
    val rssRefreshingIds by RssFeedRepository.refreshingIds.collectAsState()
    val rssDownloadStates by RssDownloadManager.states.collectAsState()
    var progressRevision by remember { mutableStateOf(0) }
    var activeLoan by remember { mutableStateOf<LoanItem?>(null) }
    var activeBook by remember {
        mutableStateOf(persistedActiveAtStartup?.toAudiobook())
    }
    val context = LocalContext.current
    var pauseLibbyWhenReady by remember { mutableStateOf(false) }
    var lastActiveLoanUrl by remember { mutableStateOf("") }
    var booksLoading by remember { mutableStateOf(false) }
    var booksMessage by remember { mutableStateOf("") }
    var libbyLibraryReadiness by remember { mutableStateOf(LibbyLibraryReadiness.NotStarted) }
    var pendingNowPlaying by remember { mutableStateOf<PersistedActiveAudiobook?>(null) }
    var showNowPlayingLoading by remember { mutableStateOf(false) }
    var showCurrentBookUnavailable by remember { mutableStateOf(false) }
    var lastBridgeUrl by remember { mutableStateOf("") }
    var hydrationStartedAt by remember { mutableStateOf(0L) }
    var lastHydratedLoanIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var stableShelfSnapshots by remember { mutableStateOf(0) }
    var libbyShelfRefreshInFlight by remember { mutableStateOf(false) }
    val localDeleteConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val book = pendingDownloadRemoval
        if (result.resultCode == Activity.RESULT_OK && book?.source == AudiobookSource.Local) {
            scope.launch { LocalBookRepository.scan() }
            if (activeBook?.source == book.source && activeBook?.id == book.id) {
                LocalPlaybackController.close()
                activeBook = null
            }
            pendingDownloadRemoval = null
            downloadRemovalMessage = ""
        } else if (book != null) {
            downloadRemovalMessage = "Could not remove audiobook."
        }
    }
    val liveLibbyBooks = remember(loans, progressRevision) { loans.map(LoanItem::toAudiobook) }
    val rememberedLibbyBooks = remember(progressRevision) {
        val persistedActive = persistedActiveAtStartup
            ?.takeIf { it.source == AudiobookSource.Libby }
            ?.toAudiobook()
        (AudiobookProgressStore.rememberedLibbyBooks() + listOfNotNull(persistedActive))
            .associateBy { it.id }
            .values
            .toList()
    }
    val libbyBooks = remember(liveLibbyBooks, rememberedLibbyBooks, libbyLibraryReadiness) {
        if (libbyLibraryReadiness == LibbyLibraryReadiness.Ready) {
            liveLibbyBooks
        } else {
            (rememberedLibbyBooks + liveLibbyBooks)
                .associateBy { AudiobookProgressStore.qualifiedId(it.source, it.id) }
                .values
                .toList()
        }
    }
    val rssBooks = remember(rssFeeds, progressRevision) {
        rssFeeds.flatMap { feed ->
            feed.books.map { book ->
                val saved = AudiobookProgressStore.read(AudiobookSource.Rss, book.id)
                book.copy(
                    positionMilliseconds = saved.positionMilliseconds,
                    durationMilliseconds = saved.durationMilliseconds.takeIf { it > 0 }
                        ?: book.durationMilliseconds,
                    playbackSpeed = saved.playbackSpeed,
                    completed = saved.completed,
                    lastPlayedAtMilliseconds = saved.lastPlayedAtMilliseconds,
                    lastUpdatedAtMilliseconds = saved.lastUpdatedAtMilliseconds,
                )
            }
        }
    }
    val displayedLocalBooks = localBooks.map { book ->
        if (activeBook?.id == book.id) {
            book.copy(
                positionMilliseconds = (localPlayerState.positionSeconds * 1000).toLong(),
                durationMilliseconds = (localPlayerState.durationSeconds * 1000).toLong()
                    .takeIf { it > 0 } ?: book.durationMilliseconds,
            )
        } else {
            book
        }
    }
    val displayedRssBooks = rssBooks.map { book ->
        if (activeBook?.source == AudiobookSource.Rss && activeBook?.id == book.id) {
            book.copy(
                positionMilliseconds = (localPlayerState.positionSeconds * 1000).toLong(),
                durationMilliseconds = (localPlayerState.durationSeconds * 1000).toLong()
                    .takeIf { it > 0 } ?: book.durationMilliseconds,
            )
        } else {
            book
        }
    }
    val allBooks = buildList {
        if (livrospeakSettings.showLibbyBooks) addAll(libbyBooks)
        if (livrospeakSettings.showLocalBooks) addAll(displayedLocalBooks)
        if (RSS_FEED_FEATURE_ENABLED && livrospeakSettings.showRssBooks) addAll(displayedRssBooks)
    }.sortedWith(
        compareByDescending<Audiobook> { it.lastPlayedAtMilliseconds }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
    val downloadedBooks = (
        localBooks + if (RSS_FEED_FEATURE_ENABLED) {
            rssBooks.mapNotNull { book ->
                RssDownloadManager.verifiedLocalReference(book)?.let {
                    book.copy(fileSizeBytes = RssDownloadManager.downloadedSizeBytes(book))
                }
            }
        } else {
            emptyList()
        }
    ).sortedBy { it.title.lowercase() }

    fun publishLibbyShelfSnapshot(found: List<LoanItem>) {
        if (libbyLibraryReadiness == LibbyLibraryReadiness.Ready && found == loans) return

        // A scrape coming back empty AFTER the shelf has already loaded successfully
        // almost always means the shared hidden WebView is mid-reload -- e.g. the
        // background genre lookup returning to /shelf once it finishes a book, which
        // can happen several times back-to-back right after a fresh install/relaunch
        // while it backfills every book that's never been looked up. The DOM briefly
        // has nothing in it while the page repaints. Without this guard, that empty
        // scrape got published straight to `loans` (the list the Books screen shows)
        // just like any other snapshot, so the visible list flickered empty-then-full
        // once per background reload -- confirmed by a real device log showing
        // exactly this many-reloads-in-a-row pattern at startup. Only ignore it while
        // we already have a good, non-empty list on screen; a genuine first-time
        // hydration (loans still empty) or an explicit manual refresh (which resets
        // readiness to Hydrating before it starts) both fall through normally, so a
        // shelf that's truly emptied out is still detected correctly.
        val alreadyShowingBooks = libbyLibraryReadiness == LibbyLibraryReadiness.Ready && loans.isNotEmpty()
        if (alreadyShowingBooks && found.isEmpty()) {
            Log.d("ListeningStats", "publishLibbyShelfSnapshot: ignoring transient empty scrape (WebView likely mid-reload)")
            return
        }

        val ids = found.map { it.loanUrl }.toSet()
        stableShelfSnapshots = if (ids == lastHydratedLoanIds) stableShelfSnapshots + 1 else 1
        lastHydratedLoanIds = ids
        loans = found

        val hydratedLongEnough = System.currentTimeMillis() - hydrationStartedAt >= 2_000L
        if (stableShelfSnapshots < 2 || !hydratedLongEnough) return

        val live = found.map(LoanItem::toAudiobook)
        AudiobookProgressStore.reconcileRememberedLibbyBooks(live)
        libbyLibraryReadiness = LibbyLibraryReadiness.Ready
        progressRevision++
        booksLoading = false
        booksMessage = if (found.isEmpty()) "No Libby audiobooks found." else ""

        val current = activeBook?.takeIf { it.source == AudiobookSource.Libby } ?: return
        val authoritative = live.firstOrNull { it.id == current.id }
        if (authoritative != null) {
            activeBook = authoritative
        } else {
            AudiobookProgressStore.clearLastActiveIfMatches(current.source, current.id)
            activeBook = null
            if (!showBooks) showCurrentBookUnavailable = true
        }
    }

    fun performLocalScan() {
        scope.launch {
            LocalBookRepository.scan()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) performLocalScan() else LocalBookRepository.publishPermissionRequired()
    }

    fun scanLocalBooks() {
        if (LocalBookRepository.hasReadPermission()) {
            performLocalScan()
        } else {
            permissionLauncher.launch(LocalBookRepository.requiredPermission())
        }
    }

    fun playNativeExclusively() {
        val libbyWasKnownPlaying = state.isPlaying
        LibbyBridge.pause()
        scope.launch {
            var pauseConfirmed = !libbyWasKnownPlaying
            repeat(6) {
                if (pauseConfirmed) return@repeat
                delay(150)
                val observed = suspendCancellableCoroutine { continuation ->
                    LibbyBridge.getPlayerState { snapshot ->
                        if (continuation.isActive) continuation.resume(snapshot)
                    }
                }
                state = observed
                if (observed.controlsFound && !observed.isPlaying) pauseConfirmed = true
            }
            if (pauseConfirmed) LocalPlaybackController.play()
        }
    }

    fun openBook(book: Audiobook, autoPlay: Boolean = false) {
        val previous = activeBook
        val requestedId = AudiobookProgressStore.qualifiedId(book.source, book.id)
        val activeId = previous?.let { AudiobookProgressStore.qualifiedId(it.source, it.id) }
        if (requestedId == activeId) {
            AudiobookProgressStore.markOpened(book)
            progressRevision++
            showBooks = false
            // Re-tapping the book that's already "active" used to just trust that the
            // shared hidden WebView was still sitting on this loan's page -- but at
            // least two other things can silently move it away while the user is
            // looking at the native Books list: the Books-tap shelf refresh below
            // (if it decides this loan page isn't "live" enough) and the background
            // genre lookup, which deliberately navigates away and parks on /shelf
            // when it finishes. Either way the WebView can end up on /shelf with no
            // player frame connected at all, while this branch still just flips back
            // to the Player screen -- so tapping Play finds nothing to talk to and
            // silently does nothing (LibbyBridge.play() drops it) until the user
            // happens to open a different loan and back, which forces a real
            // reconnect. Detect that drift the same way restoreLastPlayer() already
            // does for its own "same book" case, and reopen the loan if needed.
            if (book.source == AudiobookSource.Libby) {
                val liveRoute = LibbyWebPlayer.requireWebView().url.orEmpty()
                    .substringBefore('?')
                    .substringBefore('#')
                val rememberedRoute = book.playbackReference
                    .substringBefore('?')
                    .substringBefore('#')
                if (liveRoute != rememberedRoute) {
                    Log.d(
                        "ListeningStats",
                        "Reopening ${book.title}: WebView had drifted to $liveRoute",
                    )
                    pauseLibbyWhenReady = true
                    LibbyWebPlayer.openLoan(book.playbackReference)
                }
            }
            // Same class of bug as the Libby case above, for Local/RSS: re-tapping
            // the book that's already "active" short-circuited here unconditionally,
            // even when the native MediaPlayer setup for it had already failed
            // (readiness == Error, e.g. an unsupported codec profile or a transient
            // read failure). With nothing ever calling back into
            // LocalPlaybackController.open(), tapping the same book again did
            // nothing at all -- no new attempt, no new log line -- which is exactly
            // what made this look unfixable from a device log. Retry the same way
            // the existing RSS-download-completes effect already does elsewhere in
            // this file.
            if (
                (book.source == AudiobookSource.Local || book.source == AudiobookSource.Rss) &&
                localPlayerState.readiness == PlayerReadiness.Error
            ) {
                Log.d("ListeningStats", "Reopening ${book.title}: local player was in Error state")
                LocalPlaybackController.open(book, autoPlay = false)
            }
            return
        }
        if (previous?.source == AudiobookSource.Local || previous?.source == AudiobookSource.Rss) {
            LocalPlaybackController.persistProgress()
            if (previous.source == AudiobookSource.Local) {
                LocalBookRepository.updateBook(
                    previous.copy(
                        positionMilliseconds = (localPlayerState.positionSeconds * 1000).toLong(),
                        durationMilliseconds = (localPlayerState.durationSeconds * 1000).toLong()
                            .takeIf { it > 0 } ?: previous.durationMilliseconds,
                    ),
                )
            }
        } else if (previous?.source == AudiobookSource.Libby && state.durationSeconds > 0) {
            AudiobookProgressStore.recordSnapshot(previous, state, force = true)
        }
        val opened = AudiobookProgressStore.markOpened(book)
        val openedBook = book.copy(
            lastPlayedAtMilliseconds = opened.lastPlayedAtMilliseconds,
            lastUpdatedAtMilliseconds = opened.lastUpdatedAtMilliseconds,
        )
        activeBook = openedBook
        progressRevision++
        showBooks = false
        when (book.source) {
            AudiobookSource.Local, AudiobookSource.Rss -> {
                LibbyBridge.pause()
                if (book.source == AudiobookSource.Local) LocalBookRepository.updateBook(openedBook)
                LocalPlaybackController.open(openedBook, autoPlay = autoPlay)
            }
            AudiobookSource.Libby -> {
                LocalPlaybackController.pause()
                pauseLibbyWhenReady = true
                activeLoan = loans.firstOrNull { it.loanUrl == book.playbackReference }
                lastActiveLoanUrl = book.playbackReference
                val saved = AudiobookProgressStore.read(book.source, book.id)
                state = PlayerState(
                    title = book.title,
                    positionSeconds = saved.positionMilliseconds / 1000.0,
                    durationSeconds = saved.durationMilliseconds / 1000.0,
                    playbackSpeed = saved.playbackSpeed.toDouble(),
                    diagnostic = "Connecting to audiobook player…",
                )
                LibbyWebPlayer.openLoan(book.playbackReference)
            }
        }
    }

    fun restoreLastPlayer() {
        val persisted = AudiobookProgressStore.lastActiveAudiobook()
        if (persisted == null) {
            showCurrentBookUnavailable = true
            return
        }
        val current = activeBook
        if (current != null && AudiobookProgressStore.qualifiedId(current.source, current.id) == persisted.qualifiedId) {
            showBooks = false
            if (current.source == AudiobookSource.Libby) {
                val liveRoute = LibbyWebPlayer.requireWebView().url.orEmpty()
                    .substringBefore('?')
                    .substringBefore('#')
                val rememberedRoute = current.playbackReference
                    .substringBefore('?')
                    .substringBefore('#')
                if (liveRoute != rememberedRoute) {
                    LocalPlaybackController.pause()
                    pauseLibbyWhenReady = true
                    state = persisted.toRememberedPlayerState()
                    LibbyWebPlayer.openLoan(current.playbackReference)
                }
            }
            return
        }
        pendingNowPlaying = persisted
        showNowPlayingLoading = true
    }

    suspend fun refreshLibbyShelf() {
        if (!currentSessionReady) return

        // Guard against overlapping refreshes. Without this, pressing the manual
        // refresh button again (or the auto-refresh in the LaunchedEffect below)
        // while a refresh is already running starts a SECOND concurrent refresh
        // that shares all the same mutable state as the first -- both call
        // loadLibraryPage() (re-navigating the WebView mid-poll), both reset
        // stableShelfSnapshots/hydrationStartedAt, and whichever one's callbacks
        // land last silently wins. In practice this is what made the refresh
        // button seem to "stop doing anything" after a few quick presses: the
        // hydration-stability check kept getting reset out from under itself by
        // the next overlapping call and never settled within its own poll window.
        if (libbyShelfRefreshInFlight) return
        libbyShelfRefreshInFlight = true
        try {
            // The WebView route is authoritative -- but ONLY skip the refresh when that
            // route is a genuinely live, responsive loan page. Never replace an active
            // loan page merely because the native shelf snapshot is temporarily empty
            // during refresh/startup.
            //
            // Checking the URL alone (as this used to) is wrong: if the player frame
            // never became responsive -- or stopped being responsive -- `state` sits
            // frozen at controlsFound=false/isPlaying=false indefinitely (see the
            // "Connecting to Libby" branch in the polling LaunchedEffect below, which
            // sets exactly that). In that stuck state the WebView's `.url` keeps
            // reporting the same dead /open/loan/ page forever, so the old URL-only
            // check made every future tap of the manual refresh/sync button a no-op:
            // it kept bailing out before ever calling loadLibraryPage() or
            // re-scraping, so a loan that expired and was auto-returned server-side
            // (no longer present on Libby's real shelf) could never be detected and
            // cleared -- refresh looked like it was "doing nothing" no matter how many
            // times it was pressed. Requiring controlsFound/isPlaying here means a
            // stuck-but-URL-still-/open/loan/ page is treated as safe to navigate away
            // from and re-check, while a genuinely playing session is still left alone.
            val webViewUrl = LibbyWebPlayer.requireWebView().url.orEmpty()
            val loanPageIsLive = state.controlsFound || state.isPlaying
            if (webViewUrl.contains("/open/loan/") && loanPageIsLive) {
                currentUrl = webViewUrl
                booksLoading = false
                booksMessage = ""
                return
            }

            booksLoading = true
            booksMessage = "Loading books…"
            libbyLibraryReadiness = LibbyLibraryReadiness.Hydrating
            hydrationStartedAt = System.currentTimeMillis()
            stableShelfSnapshots = 0
            lastHydratedLoanIds = emptySet()

            // Always reload from the network here, rather than only when the
            // WebView isn't already parked on a /shelf URL. It almost always
            // already is, so that old guard meant a loan made anywhere else --
            // Livrospeak's own search/borrow flow, or the real Libby app/website --
            // never showed up: without this call nothing ever asks Libby's
            // servers for anything new, it just re-scrapes the same stale DOM
            // on a loop. This was the actual cause of newly-borrowed books not
            // appearing on the shelf.
            LibbyWebPlayer.loadLibraryPage()

            repeat(16) {
                delay(400)
                currentUrl = LibbyWebPlayer.requireWebView().url.orEmpty()
                if (currentUrl.contains("/shelf")) {
                    // Scroll before every scrape, not just once: Libby's shelf can
                    // render lazily as you scroll, so a loan further down a long
                    // shelf (loans/holds aren't necessarily sorted by due date) may
                    // simply not be in the DOM yet -- invisible to the scraper even
                    // though it's fully visible to a person scrolling the same page
                    // in a real browser. Scrolling on every poll gives newly-revealed
                    // tiles a chance to render before the next scrape, and the
                    // existing stable-snapshot check below naturally keeps waiting
                    // as long as scrolling keeps changing what's found.
                    LibbyWebPlayer.scrollToBottom()
                    LibbyWebPlayer.scrapeAudiobookLoans { found ->
                        publishLibbyShelfSnapshot(found)
                    }
                    if (libbyLibraryReadiness == LibbyLibraryReadiness.Ready) return
                }
            }

            if (libbyLibraryReadiness != LibbyLibraryReadiness.Ready) {
                libbyLibraryReadiness = LibbyLibraryReadiness.Failed
                booksLoading = false
                booksMessage = "Could not load Libby books."
            }
        } finally {
            libbyShelfRefreshInFlight = false
        }
    }

    fun refreshAllBookSources() {
        scope.launch { refreshLibbyShelf() }
        if (RSS_FEED_FEATURE_ENABLED) scope.launch { RssFeedRepository.refreshAll() }
        if (LocalBookRepository.hasReadPermission()) performLocalScan()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Libby remains mounted and rendered beneath Livrospeak's native UI.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                LibbyWebPlayer.detachFromCurrentParent()
                LibbyWebPlayer.attachToActivity(context).also { webView ->
                    val url = webView.url.orEmpty()
                    if (url.isBlank() || url == "about:blank") {
                        Log.d(TAG, "WebView was blank; loading Libby")
                        LibbyWebPlayer.loadLoginPage()
                    }
                }
            },
            update = { webView -> webView.clearFocus() },
        )

        when {
            // Checked first, before every other screen's own branch below: this can
            // be opened as an overlay from either ManageLoanScreen or the Libby search
            // confirm screen, and Back here must return to whichever one is still
            // underneath (neither of their own showX/pendingAction state gets cleared
            // when this opens) rather than needing its own copy of that logic.
            //
            // The "+ New Tag" sub-flow is checked ahead of manageTagsForTitle's own
            // branch for the same reason -- it overlays on top without clearing
            // manageTagsForTitle, so Back from it returns to this title's tag list.
            manageTagsCreatingNew && manageTagsForTitle != null -> {
                val (titleId, _) = manageTagsForTitle!!
                NewTagScreen(
                    name = manageTagsNewName,
                    onNameChange = { manageTagsNewName = it },
                    message = manageTagsNewMessage,
                    submitting = manageTagsNewSubmitting,
                    keyboardKey = "LivrospeakNewTagKeyboard:$manageTagsNewKeyboardSession",
                    onBack = {
                        manageTagsCreatingNew = false
                        manageTagsNewName = ""
                        manageTagsNewMessage = ""
                    },
                    onCreate = {
                        val name = manageTagsNewName.trim()
                        if (name.isBlank()) {
                            manageTagsNewMessage = "Enter a name for the tag."
                        } else {
                            manageTagsNewSubmitting = true
                            manageTagsNewMessage = ""
                            scope.launch {
                                // Same reason onToggleTag re-syncs before acting: time
                                // spent reading tags and typing this name is enough for
                                // the shared WebView to have drifted off the right page
                                // again since this screen's initial open.
                                manageTagsResync?.invoke()
                                val created = LibbyWebPlayer.createTagForTitle(titleId, name)
                                manageTagsNewSubmitting = false
                                if (created) {
                                    manageTagsCreatingNew = false
                                    manageTagsNewName = ""
                                    manageTagsNewMessage = ""
                                    // The new tag is already applied to this title (Libby
                                    // creates-and-applies in one step) -- re-read straight
                                    // from this title's own Save dialog again (same as
                                    // openManageTags) rather than the separate Tags list
                                    // page, so the hidden WebView never leaves this title.
                                    val (catalog, membership) = LibbyWebPlayer.scrapeSaveDialogTags(titleId)
                                    manageTagsCatalog = catalog
                                    manageTagsMembership = membership
                                } else {
                                    // Appends LibbyWebPlayer's own diagnostic (what it saw,
                                    // or didn't, when trying to open the Save dialog) --
                                    // the user has only ever been able to report this flow
                                    // failing via a screenshot of the app itself, not a
                                    // device log, so showing it here is what actually makes
                                    // the next report debuggable.
                                    val diagnostic = LibbyWebPlayer.lastSavePanelDiagnostic
                                    manageTagsNewMessage = "Could not confirm the tag was created." +
                                        diagnostic.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                                }
                            }
                        }
                    },
                )
            }

            manageTagsForTitle != null -> {
                val (titleId, titleName) = manageTagsForTitle!!
                ManageTitleTagsScreen(
                    titleName = titleName,
                    tags = manageTagsCatalog,
                    membership = manageTagsMembership,
                    loading = manageTagsLoading,
                    busyTagId = manageTagsBusyTagId,
                    message = manageTagsMessage,
                    onBack = {
                        manageTagsForTitle = null
                        manageTagsMessage = ""
                    },
                    onToggleTag = { tag ->
                        manageTagsBusyTagId = tag.id
                        manageTagsMessage = ""
                        scope.launch {
                            // Re-confirms the right page before touching the DOM --
                            // real time has passed since this screen's initial open
                            // (reading through the tag list), which is enough for the
                            // shared WebView to have drifted again. See
                            // manageTagsResync's own doc comment.
                            manageTagsResync?.invoke()
                            val toggled = LibbyWebPlayer.toggleTagForTitle(titleId, tag.name)
                            if (toggled) {
                                manageTagsMembership = LibbyWebPlayer.scrapeTagMembership(titleId)
                            } else {
                                val diagnostic = LibbyWebPlayer.lastSavePanelDiagnostic
                                manageTagsMessage = "Could not update \"${tag.displayName}\"." +
                                    diagnostic.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                            }
                            // Clearing busyTagId used to happen the instant
                            // toggleTagForTitle returned -- BEFORE the membership
                            // re-scrape above even ran -- which read as the "Updating…"
                            // indicator disappearing too early relative to when the
                            // toggle actually finishes settling. Moved to the end (past
                            // the re-scrape) and given an extra couple of seconds on
                            // top, per direct feedback that it should outlast Libby's
                            // own toggle animation rather than racing to clear the
                            // instant our own click-and-confirm completes.
                            delay(2000)
                            manageTagsBusyTagId = null
                        }
                    },
                    onNewTag = {
                        manageTagsCreatingNew = true
                        manageTagsNewKeyboardSession = System.currentTimeMillis()
                    },
                )
            }

            pendingDownloadRemoval != null -> {
                val book = pendingDownloadRemoval!!
                DownloadRemovalConfirmationScreen(
                    book = book,
                    message = downloadRemovalMessage,
                    onBack = {
                        pendingDownloadRemoval = null
                        downloadRemovalMessage = ""
                    },
                    onConfirm = {
                        downloadRemovalMessage = ""
                        scope.launch {
                            val removed = when (book.source) {
                                AudiobookSource.Local -> LocalBookRepository.deleteBook(book)
                                AudiobookSource.Rss -> {
                                    RssDownloadManager.removeDownload(book)
                                    true
                                }
                                AudiobookSource.Libby -> false
                            }
                            if (removed) {
                                if (
                                    activeBook?.source == book.source &&
                                    activeBook?.id == book.id &&
                                    book.source == AudiobookSource.Local
                                ) {
                                    LocalPlaybackController.close()
                                    activeBook = null
                                }
                                pendingDownloadRemoval = null
                            } else if (book.source == AudiobookSource.Local) {
                                val request = runCatching {
                                    MediaStore.createDeleteRequest(
                                        context.contentResolver,
                                        listOf(Uri.parse(book.playbackReference)),
                                    )
                                }.getOrNull()
                                if (request != null) {
                                    localDeleteConsentLauncher.launch(
                                        IntentSenderRequest.Builder(request.intentSender).build(),
                                    )
                                } else {
                                    downloadRemovalMessage = "Could not remove audiobook."
                                }
                            } else {
                                downloadRemovalMessage = "Could not remove audiobook."
                            }
                        }
                    },
                )
            }

            showManageLoan && activeBook != null -> {
                val book = activeBook!!
                ManageLoanScreen(
                    book = book,
                    message = manageLoanMessage,
                    busyAction = manageLoanBusyAction,
                    onBack = {
                        showManageLoan = false
                        manageLoanMessage = ""
                    },
                    onManageTags = {
                        val ids = LibbyWebPlayer.loanIdsFromUrl(book.playbackReference)
                        if (ids == null) {
                            manageLoanMessage = "Could not identify this loan."
                        } else {
                            // This is a known owned loan, so the hidden WebView needs
                            // to be sent back to the shelf first -- see
                            // openManageTags's own doc comment for why it's almost
                            // never still there by the time this runs. A fixed-count
                            // scroll-and-wait here (what used to be inline) was
                            // confirmed via retest to still fail "no-tile" for an
                            // owned loan, since it never actually checked whether the
                            // tile had rendered before moving on -- resyncLoanShelfForTitle
                            // polls for that specific tile instead, with the same
                            // margin as the shelf hydration loop already proven
                            // reliable for the Books screen itself. Passed as [resync]
                            // rather than a one-time flag so it reruns before every
                            // later toggle/create too.
                            openManageTags(ids.second, book.title, resync = {
                                LibbyWebPlayer.resyncLoanShelfForTitle(ids.second)
                            })
                        }
                    },
                    onReturnLoan = {
                        val ids = LibbyWebPlayer.loanIdsFromUrl(book.playbackReference)
                        if (ids == null) {
                            manageLoanMessage = "Could not identify this loan."
                        } else {
                            manageLoanMessage = ""
                            manageLoanBusyAction = LoanAction.Return
                            LibbyBridge.pause()
                            scope.launch {
                                val (cardId, titleId) = ids
                                val returned = LibbyWebPlayer.returnLoan(cardId, titleId)
                                manageLoanBusyAction = null
                                if (returned) {
                                    AudiobookProgressStore.forgetLibbyBook(book.id)
                                    progressRevision++
                                    activeBook = null
                                    showManageLoan = false
                                    manageLoanMessage = ""
                                    showBooks = true
                                } else {
                                    manageLoanMessage = "Could not confirm the loan was returned."
                                }
                            }
                        }
                    },
                    onRenewLoan = {
                        val ids = LibbyWebPlayer.loanIdsFromUrl(book.playbackReference)
                        if (ids == null) {
                            manageLoanMessage = "Could not identify this loan."
                        } else {
                            manageLoanMessage = ""
                            manageLoanBusyAction = LoanAction.Renew
                            LibbyBridge.pause()
                            scope.launch {
                                val (cardId, titleId) = ids
                                val renewed = LibbyWebPlayer.renewLoan(cardId, titleId)
                                manageLoanBusyAction = null
                                manageLoanMessage = if (renewed) {
                                    "Loan renewed."
                                } else {
                                    "Could not confirm the loan was renewed."
                                }
                            }
                        }
                    },
                )
            }

            showCurrentBookUnavailable -> {
                CurrentBookStatusScreen(
                    message = "No current open book",
                    onBack = {
                        showCurrentBookUnavailable = false
                        showBooks = true
                    },
                )
            }

            showNowPlayingLoading -> {
                CurrentBookStatusScreen(
                    message = "Opening current book…",
                    onBack = {
                        pendingNowPlaying = null
                        showNowPlayingLoading = false
                        showBooks = true
                    },
                )
            }

            showLibbyConnect -> {
                LibbyConnectScreen(
                    state = connectState,
                    onBack = {
                        LibbyConnectCoordinator.cancelConnection()
                        showLibbyConnect = false
                    },
                    onNewCode = LibbyConnectCoordinator::requestNewCode,
                    onRetry = LibbyConnectCoordinator::startConnection,
                    onCancel = {
                        LibbyConnectCoordinator.cancelConnection()
                        showLibbyConnect = false
                    },
                )
            }

            showDisconnectConfirmation -> {
                DisconnectLibbyScreen(
                    onBack = { showDisconnectConfirmation = false },
                    onDisconnect = {
                        LibbyConnectCoordinator.disconnect()
                        loans = emptyList()
                        activeLoan = null
                        lastActiveLoanUrl = ""
                        state = PlayerState()
                        showDisconnectConfirmation = false
                        showLibbySource = false
                        showSettings = false
                        showBooks = true
                    },
                    onCancel = { showDisconnectConfirmation = false },
                )
            }

            showResetLibraryCardPinConfirmation -> {
                ResetLibraryCardPinScreen(
                    onBack = { showResetLibraryCardPinConfirmation = false },
                    onReset = {
                        LibraryCardPinStore.resetPin()
                        showResetLibraryCardPinConfirmation = false
                    },
                    onCancel = { showResetLibraryCardPinConfirmation = false },
                )
            }

            showLocalBooks -> {
                LocalBooksScreen(
                    result = localScanResult,
                    scanning = localScanning,
                    onBack = { showLocalBooks = false },
                    onScan = ::scanLocalBooks,
                )
            }

            showAddRssFeed -> {
                AddRssFeedScreen(
                    onBack = { showAddRssFeed = false },
                    onSubmit = { url ->
                        when (val result = RssFeedRepository.addFeed(url)) {
                            is RssFeedResult.Success -> {
                                showAddRssFeed = false
                                null
                            }
                            is RssFeedResult.Error -> result.userMessage
                        }
                    },
                )
            }

            selectedRssFeedId != null -> {
                val feed = rssFeeds.firstOrNull { it.id == selectedRssFeedId }
                if (feed == null) {
                    selectedRssFeedId = null
                } else {
                    RssFeedDetailScreen(
                        feed = feed,
                        refreshing = feed.id in rssRefreshingIds,
                        message = rssFeedMessage,
                        onBack = { selectedRssFeedId = null },
                        onRefresh = {
                            rssFeedMessage = ""
                            scope.launch {
                                rssFeedMessage = when (val result = RssFeedRepository.refreshFeed(feed.id)) {
                                    is RssFeedResult.Success -> "Feed refreshed."
                                    is RssFeedResult.Error -> result.userMessage
                                }
                            }
                        },
                        onRemove = {
                            scope.launch {
                                RssFeedRepository.removeFeed(feed.id)
                                selectedRssFeedId = null
                            }
                        },
                    )
                }
            }

            showRssFeeds -> {
                RssFeedsScreen(
                    feeds = rssFeeds,
                    onBack = { showRssFeeds = false },
                    onAddFeed = { showAddRssFeed = true },
                    onOpenFeed = {
                        rssFeedMessage = ""
                        selectedRssFeedId = it.id
                    },
                )
            }

            showLibbySource -> {
                LibbySourceScreen(
                    sessionState = libbySessionState,
                    hasLibraryCardPin = LibraryCardPinStore.hasPin(),
                    onBack = { showLibbySource = false },
                    onConnect = {
                        showLibbyConnect = true
                        LibbyConnectCoordinator.startConnection()
                    },
                    onDisconnect = { showDisconnectConfirmation = true },
                    onResetLibraryCardPin = { showResetLibraryCardPinConfirmation = true },
                )
            }

            showDownloads -> {
                DownloadsScreen(
                    books = downloadedBooks,
                    editMode = downloadsEditMode,
                    onBack = {
                        downloadsEditMode = false
                        showDownloads = false
                    },
                    onToggleEdit = { downloadsEditMode = !downloadsEditMode },
                    onOpenBook = ::openBook,
                    onRemoveBook = { pendingDownloadRemoval = it },
                )
            }

            showAudiobookSources -> {
                // Must be checked before `showSettings` below: opening this
                // sub-screen from Settings never clears showSettings (same as
                // every other Settings sub-screen -- Libby/Local Books/RSS
                // Feeds all work the same way), so with the branches
                // in the other order this screen would never render until
                // Back cleared showSettings first.
                AudiobookSourceSettingsScreen(
                    settings = livrospeakSettings,
                    onShowLibbyBooksChange = LivrospeakSettingsStore::setShowLibbyBooks,
                    onShowLocalBooksChange = LivrospeakSettingsStore::setShowLocalBooks,
                    onShowRssBooksChange = LivrospeakSettingsStore::setShowRssBooks,
                    onBack = { showAudiobookSources = false },
                )
            }

            showSettings -> {
                SettingsScreen(
                    settings = livrospeakSettings,
                    onInvertColorsChange = { enabled ->
                        LivrospeakSettingsStore.setInvertColors(enabled)
                        if (enabled) {
                            LightThemeController.setLightTheme()
                        } else {
                            LightThemeController.setDarkTheme()
                        }
                    },
                    onBack = { showSettings = false },
                    onOpenAudiobookSources = { showAudiobookSources = true },
                    onOpenLibby = { showLibbySource = true },
                    onOpenLocalBooks = { showLocalBooks = true },
                    onOpenRssFeeds = { showRssFeeds = true },
                    onListeningStatsEnabledChange = LivrospeakSettingsStore::setListeningStatsEnabled,
                    onListeningStatsColorfulChange = LivrospeakSettingsStore::setListeningStatsColorful,
                    onListeningStreakEnabledChange = LivrospeakSettingsStore::setListeningStreakEnabled,
                )
            }

            showLibbySearch -> {
                LibbySearchScreen(
                    onBack = { showLibbySearch = false },
                    onBorrowed = {
                        // A fresh loan was added; drop back to Books and let the existing
                        // shelf-polling LaunchedEffect (below) pick it up on its next pass.
                        showLibbySearch = false
                        showBooks = true
                    },
                    // Not an owned loan, so NOT sent to the shelf -- a search result
                    // isn't borrowed yet and wouldn't be there (see openManageTags's
                    // own doc comment). The resync lambda LibbySearchScreen hands back
                    // here is its own resyncSearchResultPage(result), already confirmed
                    // working once by requestManageTags before this is even called.
                    onManageTags = { titleId, titleName, resync -> openManageTags(titleId, titleName, resync) },
                )
            }

            showListeningStats -> {
                ListeningStatsScreen(
                    colorful = livrospeakSettings.listeningStatsColorful,
                    streakEnabled = livrospeakSettings.listeningStreakEnabled,
                    onBack = { showListeningStats = false },
                )
            }

            // Must be checked before `showLibbyTools` below, same reason
            // showAudiobookSources is checked before showSettings above: a row tap
            // sets this without clearing showLibbyTools, so Back from the
            // placeholder lands back on the hub screen instead of Books.
            libbyToolsPlaceholderMessage != null -> {
                CurrentBookStatusScreen(
                    message = libbyToolsPlaceholderMessage.orEmpty(),
                    onBack = { libbyToolsPlaceholderMessage = null },
                )
            }

            // Same nesting idea: a hold tap sets activeHold without clearing
            // showHolds, so Back from Manage Hold returns to the Holds list rather
            // than all the way to the hub.
            activeHold != null -> {
                val hold = activeHold!!
                ManageHoldScreen(
                    hold = hold,
                    message = manageHoldMessage,
                    busyAction = manageHoldBusyAction,
                    onBack = {
                        activeHold = null
                        manageHoldMessage = ""
                    },
                    onCancelHold = {
                        manageHoldMessage = ""
                        manageHoldBusyAction = HoldAction.Cancel
                        scope.launch {
                            val cancelled = LibbyWebPlayer.cancelHold(hold.cardId, hold.titleId)
                            manageHoldBusyAction = null
                            if (cancelled) {
                                activeHold = null
                                manageHoldMessage = ""
                                refreshHolds()
                            } else {
                                manageHoldMessage = "Could not confirm the hold was cancelled."
                            }
                        }
                    },
                    onSuspendHold = {
                        manageHoldMessage = ""
                        manageHoldBusyAction = HoldAction.Suspend
                        scope.launch {
                            val suspended = LibbyWebPlayer.suspendHold(hold.cardId, hold.titleId)
                            manageHoldBusyAction = null
                            manageHoldMessage = if (suspended) {
                                "Hold suspended."
                            } else {
                                "Could not confirm the hold was suspended."
                            }
                            if (suspended) refreshHolds()
                        }
                    },
                )
            }

            showHolds -> {
                HoldsScreen(
                    holds = holds,
                    loading = holdsLoading,
                    message = holdsMessage,
                    onBack = { showHolds = false },
                    onOpenHold = { activeHold = it },
                )
            }

            // Checked ahead of activeTag's own branch, same overlay pattern as the
            // "+ New Tag" sub-flow over ManageTitleTagsScreen -- Back returns to the
            // tag's own detail view, not all the way out to the Tags list.
            renamingTag && activeTag != null -> {
                val tag = activeTag!!
                RenameTagScreen(
                    name = renameTagName,
                    onNameChange = { renameTagName = it },
                    message = renameTagMessage,
                    submitting = renameTagSubmitting,
                    keyboardKey = "LivrospeakRenameTagKeyboard:$renameTagKeyboardSession",
                    onBack = {
                        renamingTag = false
                        renameTagMessage = ""
                    },
                    onDone = {
                        val newName = renameTagName.trim()
                        if (newName.isBlank()) {
                            renameTagMessage = "Enter a name for the tag."
                        } else {
                            renameTagSubmitting = true
                            renameTagMessage = ""
                            scope.launch {
                                val renamed = LibbyWebPlayer.renameTag(tag.id, newName)
                                renameTagSubmitting = false
                                if (renamed) {
                                    renamingTag = false
                                    renameTagMessage = ""
                                    // The tag's real id (the /tags/tag/ URL slug) may
                                    // itself be derived from the name and change on
                                    // rename -- backing out to the refreshed list
                                    // rather than guessing at a still-valid activeTag
                                    // reference avoids acting on a stale id afterward.
                                    activeTag = null
                                    refreshTags()
                                } else {
                                    // Same idea as the "+ New Tag" failure message above --
                                    // appends LibbyWebPlayer's own diagnostic for exactly
                                    // which step (ACTIONS button, Rename choice, name input,
                                    // Done button, or the final verify re-scrape) didn't find
                                    // what it expected.
                                    val diagnostic = LibbyWebPlayer.lastTagActionDiagnostic
                                    renameTagMessage = "Could not confirm the tag was renamed." +
                                        diagnostic.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                                }
                            }
                        }
                    },
                )
            }

            confirmingDeleteTag && activeTag != null -> {
                val tag = activeTag!!
                ConfirmDeleteTagScreen(
                    tagName = tag.displayName,
                    message = deleteTagMessage,
                    submitting = deleteTagSubmitting,
                    onBack = {
                        confirmingDeleteTag = false
                        deleteTagMessage = ""
                    },
                    onDelete = {
                        deleteTagSubmitting = true
                        deleteTagMessage = ""
                        scope.launch {
                            val deleted = LibbyWebPlayer.deleteTag(tag.id)
                            deleteTagSubmitting = false
                            if (deleted) {
                                confirmingDeleteTag = false
                                deleteTagMessage = ""
                                activeTag = null
                                refreshTags()
                            } else {
                                // Same idea as RenameTagScreen's failure message above.
                                val diagnostic = LibbyWebPlayer.lastTagActionDiagnostic
                                deleteTagMessage = "Could not confirm the tag was deleted." +
                                    diagnostic.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                            }
                        }
                    },
                )
            }

            // Same nesting idea again: opening a tag sets its own flag without
            // clearing showTags, so Back returns to the Tags list rather than all
            // the way to the hub. There's no standalone "new tag" entry here anymore
            // -- creation only happens from within a title's Manage Tags screen (see
            // the manageTagsCreatingNew branch above), since that's the only real
            // Libby flow for it.
            activeTag != null -> {
                val tag = activeTag!!
                TagDetailScreen(
                    tag = tag,
                    titles = tagTitles,
                    ownedTitleIds = loans.mapNotNull { LibbyWebPlayer.loanIdsFromUrl(it.loanUrl)?.second }.toSet(),
                    loading = tagTitlesLoading,
                    message = tagTitlesMessage,
                    onBack = { activeTag = null },
                    onRename = {
                        renameTagName = tag.name
                        renameTagMessage = ""
                        renameTagKeyboardSession = System.currentTimeMillis()
                        renamingTag = true
                    },
                    onDelete = {
                        deleteTagMessage = ""
                        confirmingDeleteTag = true
                    },
                )
            }

            showTags -> {
                TagsScreen(
                    tags = tags,
                    loading = tagsLoading,
                    message = tagsMessage,
                    onBack = { showTags = false },
                    onOpenTag = ::openTag,
                )
            }

            // Same nesting idea as Tags/Holds: opening this sets its own flag
            // without clearing showLibbyTools, so Back returns to the hub rather
            // than all the way to Books.
            showLibraryCards -> {
                LibraryCardScreen(onBack = { showLibraryCards = false })
            }

            showLibbyTools -> {
                LibbyToolsScreen(
                    onBack = { showLibbyTools = false },
                    onOpenTags = {
                        showTags = true
                        refreshTags()
                    },
                    onOpenHolds = {
                        showHolds = true
                        refreshHolds()
                    },
                    onOpenLibraryCards = { showLibraryCards = true },
                )
            }

            showBooks -> {
                BooksScreen(
                    books = allBooks,
                    loading = booksLoading,
                    message = booksMessage,
                    hasDownloads = downloadedBooks.isNotEmpty(),
                    libbyConnected = sessionReady,
                    showListeningStatsButton = livrospeakSettings.listeningStatsEnabled,
                    onListeningStats = { showListeningStats = true },
                    onSettings = { showSettings = true },
                    onSearch = { showLibbySearch = true },
                    onDownloads = { showDownloads = true },
                    onRefresh = ::refreshAllBookSources,
                    onMenu = { showLibbyTools = true },
                    onOpenBook = ::openBook,
                )
            }

            else -> {
                val nativeActive = activeBook?.source != AudiobookSource.Libby
                val rssDownloadState = activeBook
                    ?.takeIf { it.source == AudiobookSource.Rss }
                    ?.let { rssDownloadStates[it.id] }
                val activeRssFeedId = activeBook
                    ?.takeIf { it.source == AudiobookSource.Rss }
                    ?.let { book -> rssFeeds.firstOrNull { feed -> feed.books.any { it.id == book.id } }?.id }
                PlayerScreen(
                    state = if (nativeActive) localPlayerState else state,
                    book = activeBook,
                    onPlay = if (nativeActive) {
                        ::playNativeExclusively
                    } else {
                        {
                            LocalPlaybackController.pause()
                            LibbyBridge.play()
                        }
                    },
                    onPause = if (nativeActive) {
                        LocalPlaybackController::pause
                    } else {
                        {
                            LibbyBridge.pause()
                            activeBook?.takeIf { it.source == AudiobookSource.Libby }?.let {
                                AudiobookProgressStore.recordSnapshot(it, state, force = true)
                            }
                            Unit
                        }
                    },
                    onBack15 = if (nativeActive) {
                        { LocalPlaybackController.seekBy(-15_000) }
                    } else LibbyBridge::back15,
                    onForward15 = if (nativeActive) {
                        { LocalPlaybackController.seekBy(15_000) }
                    } else LibbyBridge::forward15,
                    onSeekTo = if (nativeActive) {
                        LocalPlaybackController::seekTo
                    } else {
                        { position ->
                            LibbyBridge.seekTo(position)
                            activeBook?.takeIf { it.source == AudiobookSource.Libby }?.let {
                                AudiobookProgressStore.recordSnapshot(
                                    it,
                                    state.copy(positionSeconds = position / 1000.0),
                                    force = true,
                                )
                            }
                            Unit
                        }
                    },
                    onSetSpeed = if (nativeActive) LocalPlaybackController::setSpeed else LibbyBridge::setSpeed,
                    downloadStatus = when (activeBook?.source) {
                        AudiobookSource.Local -> null
                        AudiobookSource.Rss -> rssDownloadState?.status ?: RssDownloadStatus.NotDownloaded
                        AudiobookSource.Libby, null -> null
                    },
                    onDownload = activeBook
                        ?.takeIf { it.source == AudiobookSource.Rss && activeRssFeedId != null }
                        ?.let { book -> { RssDownloadManager.start(book, activeRssFeedId!!) } },
                    statusText = when {
                        activeBook?.source == AudiobookSource.Libby && libbyPlaybackHostFailed ->
                            "Could not keep Libby playback active"
                        activeBook?.source == AudiobookSource.Libby && libbyRendererFailed ->
                            "Libby player disconnected"
                        activeBook?.source == AudiobookSource.Libby &&
                            state.readiness != PlayerReadiness.Ready -> "Connecting to Libby"
                        localPlayerState.readiness == PlayerReadiness.Preparing &&
                            activeBook?.source == AudiobookSource.Rss -> "Preparing…"
                        localPlayerState.readiness == PlayerReadiness.Buffering &&
                            activeBook?.source == AudiobookSource.Rss -> "Buffering"
                        rssDownloadState?.status == RssDownloadStatus.Downloading -> "Downloading"
                        rssDownloadState?.status == RssDownloadStatus.Downloaded -> "Downloaded"
                        rssDownloadState?.lastError == "Not enough storage" -> "Not enough storage"
                        localPlayerState.readiness == PlayerReadiness.Error &&
                            activeBook?.source == AudiobookSource.Rss -> "Not available offline"
                        rssDownloadState?.status == RssDownloadStatus.Failed -> "Download failed"
                        else -> ""
                    },
                    onBooks = {
                        Log.d("ListeningStats", "onBooks tapped: showBooks=true, currentUrl=$currentUrl")
                        showBooks = true
                        booksLoading = false
                        booksMessage = ""
                    },
                    onManageLoan = activeBook
                        ?.takeIf { it.source == AudiobookSource.Libby }
                        ?.let { { showManageLoan = true } },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        LibbyConnectCoordinator.refreshSessionState()
        while (true) {
            val webView = LibbyWebPlayer.requireWebView()
            currentUrl = webView.url.orEmpty()

            if (currentUrl != lastBridgeUrl) {
                lastBridgeUrl = currentUrl
                LibbyBridge.onTopLevelUrlChanged(currentUrl)

                if (currentUrl.contains("/open/loan/")) {
                    showBooks = false
                    lastActiveLoanUrl = currentUrl.substringBefore('?').substringBefore('#')
                    val previousLoanUrl = activeLoan?.loanUrl
                    val matchedLoan = loans.firstOrNull { currentUrl.startsWith(it.loanUrl) } ?: activeLoan
                    val loanActuallyChanged = matchedLoan?.loanUrl != previousLoanUrl
                    activeLoan = matchedLoan
                    activeBook = activeLoan?.toAudiobook()
                        ?: activeBook

                    // Only reset to "Connecting to Libby" (and drop isPlaying) when the
                    // loan itself genuinely changed -- not merely because the raw URL
                    // string differs from last tick's. Libby's own player page can tweak
                    // its URL during normal playback of the SAME loan (a query param, a
                    // hash, e.g. on a chapter change) while `currentUrl.startsWith(it.loanUrl)`
                    // still matches the same loan. Re-arming "Preparing"/isPlaying=false on
                    // every one of those tweaks was fragmenting real, continuous listening
                    // into sub-5-second pieces -- each one individually below
                    // ListeningSessionStore's MINIMUM_SESSION_MILLISECONDS threshold and
                    // therefore silently discarded -- so no session ever got recorded even
                    // after minutes of uninterrupted playback.
                    if (loanActuallyChanged) {
                        Log.d(
                            "ListeningStats",
                            "Loan changed (was $previousLoanUrl, now ${matchedLoan?.loanUrl}), " +
                                "resetting player state to Connecting",
                        )
                        state = state.copy(
                            isPlaying = false,
                            controlsFound = false,
                            diagnostic = "Connecting to Libby",
                            readiness = PlayerReadiness.Preparing,
                        )
                    } else {
                        Log.d(
                            "ListeningStats",
                            "URL churned within same loan ($previousLoanUrl) -- player state left alone",
                        )
                    }
                }
            }

            if (currentSessionReady && currentUrl.contains("/open/loan/")) {
                LibbyBridge.getPlayerState { newState ->
                    state = newState
                    if (pauseLibbyWhenReady && (newState.controlsFound || newState.durationSeconds > 0)) {
                        // A freshly opened Libby loan may report a ready-but-paused snapshot before
                        // its own delayed autoplay begins. Send one unconditional pause while the
                        // player frame is authoritative, then retire the cold-restore guard.
                        LibbyBridge.pause()
                        pauseLibbyWhenReady = false
                    }
                    activeBook?.takeIf {
                        it.source == AudiobookSource.Libby && newState.durationSeconds > 0
                    }?.let {
                        AudiobookProgressStore.recordSnapshot(it, newState)
                    }
                }
            }

            if (currentSessionReady && currentUrl.contains("/shelf")) {
                LibbyWebPlayer.scrapeAudiobookLoans { found ->
                    publishLibbyShelfSnapshot(found)
                }
            }

            // Best-effort Libby genre lookup for the Listening Stats screen -- fetch
            // lazily, cache forever, one book per idle window, and ONLY when the
            // WebView is genuinely idle: nothing currently loading, and nothing
            // actually playing (Libby or local/RSS). This mirrors the same safety
            // reasoning as the loan-change fix above: this shared WebView's navigation
            // state has already caused two separate bugs this app cycle, so genre
            // lookups deliberately never compete with anything the user is actively
            // doing. See LibbyWebPlayer.fetchLoanGenres's own doc comment for the
            // navigate-and-return mechanics.
            //
            // "Idle" is NOT the same as "currentUrl is on /shelf" -- confirmed by a
            // real test that came back with every genre still "Unknown" after 20
            // minutes of listening: nothing ever re-navigates the hidden WebView back
            // to the shelf after playback just because the user tapped back to the
            // native Books screen (only the manual refresh button, or reconnecting,
            // calls loadLibraryPage()). Left at currentUrl.contains("/shelf") alone,
            // the WebView sits parked on the now-paused /open/loan/ page indefinitely
            // and this condition never becomes true again. A paused loan page counts
            // as idle too, as long as the user has actually navigated back to the
            // native Books list (showBooks) rather than still looking at that book's
            // player screen -- reusing the same "is this loan page actually live"
            // reasoning as refreshLibbyShelf's own fix.
            val idleOnShelf = currentUrl.contains("/shelf")
            val idleOnPausedLoanPage = showBooks && currentUrl.contains("/open/loan/") && !state.isPlaying
            // "idleOnShelf" alone isn't enough to call the shared WebView idle:
            // Tags/Holds/Library Cards all deliberately park it on a /shelf* route
            // too (openManageTags's new ensureOnShelf path included), and this loop
            // would otherwise be free to yank it away to a title-details page for a
            // genre lookup in the MIDDLE of one of those flows -- confirmed as a real
            // risk once openManageTags started navigating to the shelf itself this
            // round, not just a theoretical one. Reuses the existing screen-state
            // flags rather than adding a new one.
            val libbyToolsScreenActive = showTags || activeTag != null ||
                manageTagsForTitle != null || manageTagsCreatingNew ||
                showHolds || activeHold != null || showLibraryCards
            val genreIdleGateOpen = currentSessionReady &&
                (idleOnShelf || idleOnPausedLoanPage) &&
                !booksLoading &&
                !state.isPlaying &&
                !localPlayerState.isPlaying &&
                !genreFetchInProgress &&
                !libbyToolsScreenActive
            // Edge-triggered (only logs on change, not every 700ms tick) so a real
            // device capture stays readable -- this makes every gating variable
            // visible at once instead of needing another guess-and-retest round if
            // the fetch still doesn't fire.
            val genreDebugSnapshot = "gateOpen=$genreIdleGateOpen sessionReady=$currentSessionReady " +
                "showBooks=$showBooks url=$currentUrl booksLoading=$booksLoading " +
                "libbyPlaying=${state.isPlaying} localPlaying=${localPlayerState.isPlaying} " +
                "fetchInProgress=$genreFetchInProgress libbyToolsActive=$libbyToolsScreenActive"
            if (genreDebugSnapshot != lastGenreDebugSnapshot) {
                lastGenreDebugSnapshot = genreDebugSnapshot
                Log.d("ListeningStats", "Genre idle gate: $genreDebugSnapshot")
            }
            if (genreIdleGateOpen) {
                val needsGenre = loans.firstOrNull { loan -> !GenreStore.hasAttempted(loan.loanUrl) }
                if (needsGenre != null) {
                    val titleId = needsGenre.loanUrl.substringAfterLast('/')
                    val bookId = needsGenre.loanUrl
                    genreFetchInProgress = true
                    Log.d("ListeningStats", "Fetching Libby genre for $bookId (titleId=$titleId)")
                    launch {
                        val genres = runCatching { LibbyWebPlayer.fetchLoanGenres(titleId) }
                            .getOrDefault(emptyList())
                        Log.d("ListeningStats", "Genre result for $bookId: $genres")
                        GenreStore.record(bookId, genres)
                        genreFetchInProgress = false
                    }
                }
            }

            // Feeds the Listening Stats session log. Deliberately computed once per
            // tick from the same values the player UI itself reads, rather than from
            // individual play()/pause() call sites (see ListeningSessionStore's doc
            // comment for why). `state.isPlaying` is only trusted while currentUrl is
            // actually still on a /open/loan/ page -- once the WebView navigates away
            // (back to shelf, etc.) `state` simply stops being refreshed and can be
            // left stale at isPlaying=true, which would otherwise make a session look
            // like it kept running long after the player screen was gone.
            val libbyActuallyPlaying = currentUrl.contains("/open/loan/") && state.isPlaying
            val effectiveIsPlaying = when (activeBook?.source) {
                AudiobookSource.Libby -> libbyActuallyPlaying
                AudiobookSource.Local, AudiobookSource.Rss -> localPlayerState.isPlaying
                null -> false
            }
            ListeningSessionStore.onPlaybackTick(activeBook, effectiveIsPlaying)

            delay(700)
        }
    }

    LaunchedEffect(libbySessionState) {
        libbyLibraryReadiness = when (libbySessionState) {
            LibbySessionState.Checking -> LibbyLibraryReadiness.Connecting
            LibbySessionState.Disconnected -> LibbyLibraryReadiness.Disconnected
            LibbySessionState.Connected -> when (libbyLibraryReadiness) {
                LibbyLibraryReadiness.Ready,
                LibbyLibraryReadiness.Hydrating -> libbyLibraryReadiness
                else -> LibbyLibraryReadiness.Connecting
            }
        }
    }

    LaunchedEffect(connectState) {
        if (connectState is LibbyConnectState.Connected) {
            delay(500)
            showLibbyConnect = false
            showLibbySource = false
            showSettings = false
            showBooks = true
            booksLoading = true
            booksMessage = "Loading books…"
            libbyLibraryReadiness = LibbyLibraryReadiness.Hydrating
            hydrationStartedAt = System.currentTimeMillis()
            stableShelfSnapshots = 0
            lastHydratedLoanIds = emptySet()
            LibbyWebPlayer.loadLibraryPage()
        }
    }

    LaunchedEffect(Unit) {
        if (LocalBookRepository.hasReadPermission()) performLocalScan()
    }

    if (RSS_FEED_FEATURE_ENABLED) {
        LaunchedEffect(Unit) {
            RssFeedRepository.refreshAll()
        }
    }

    LaunchedEffect(rssDownloadStates, localPlayerState.readiness, activeBook) {
        val book = activeBook?.takeIf { it.source == AudiobookSource.Rss } ?: return@LaunchedEffect
        if (
            localPlayerState.readiness == PlayerReadiness.Error &&
            RssDownloadManager.verifiedLocalReference(book) != null
        ) {
            LocalPlaybackController.open(book, autoPlay = false)
        }
    }

    LaunchedEffect(showBooks, sessionReady) {
        if (!showBooks || !sessionReady) return@LaunchedEffect
        refreshLibbyShelf()
    }

    LaunchedEffect(
        pendingNowPlaying,
        allBooks,
        localScanResult,
        localScanning,
        libbySessionState,
        libbyLibraryReadiness,
    ) {
        val persisted = pendingNowPlaying ?: return@LaunchedEffect
        val resolved = allBooks.firstOrNull {
            AudiobookProgressStore.qualifiedId(it.source, it.id) == persisted.qualifiedId
        }
        if (resolved != null) {
            pendingNowPlaying = null
            showNowPlayingLoading = false
            openBook(resolved, autoPlay = false)
            return@LaunchedEffect
        }

        val resolutionFinished = when (persisted.source) {
            AudiobookSource.Local -> !LocalBookRepository.hasReadPermission() ||
                (!localScanning && localScanResult != null)
            AudiobookSource.Libby -> when (libbySessionState) {
                LibbySessionState.Checking -> false
                LibbySessionState.Connected ->
                    libbyLibraryReadiness == LibbyLibraryReadiness.Ready
                LibbySessionState.Disconnected -> true
            }
            AudiobookSource.Rss -> rssReady
        }
        if (resolutionFinished) {
            pendingNowPlaying = null
            showNowPlayingLoading = false
            showCurrentBookUnavailable = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { LibbyWebPlayer.detachFromCurrentParent() }
    }
}

@Composable
private fun CurrentBookStatusScreen(message: String, onBack: () -> Unit) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Books",
                ),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = message,
                    variant = LightTextVariant.Paragraph,
                    align = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun LivrospeakSurface(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

/**
 * Native LightOS toggle row: icon on the LEFT, label on the RIGHT. Toggling OFF shows
 * [LightIcons.TOGGLE_STATE_OFF]; toggling ON shows [LightIcons.TOGGLE_STATE_ON]. Icon
 * size matches [LightBarButtonDefaults.ICON_SIZE_UNITS], the same standard size used
 * for every other icon in the app (top bar back button, bottom bar icons).
 */
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    description: String? = null,
    // Defaults to false so every existing ToggleRow call site (Settings' various
    // on/off rows) is unaffected. ManageTitleTagsScreen is the one caller that
    // passes true, for the specific tag currently mid-toggle -- confirmed via a
    // real report that a toggle's round trip (re-navigate, click, reverify) has a
    // noticeable delay with nothing on screen showing it's in progress, which
    // invites a user to tap again thinking the first tap didn't register.
    busy: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The fixed 5.5-unit height (unchanged for every other ToggleRow) is
                // what centers the icon+label -- fine on its own, but when a
                // description follows immediately below, that same centering leaves a
                // visually "disjointed" gap: half the row's empty space sits BELOW the
                // label before the row even ends, on top of whatever spacing follows.
                // Shrinking to a smaller min height only in this variant (still a
                // reasonable tap target) tightens that gap without touching the many
                // other ToggleRow usages that have no description.
                .then(
                    if (description != null) {
                        // Tightened once already (5.5 -> 3.5); still reported as a
                        // bit loose, so tightened again closer to the icon's own
                        // size (2 units) plus a little breathing room.
                        Modifier.heightIn(min = 2.5f.gridUnitsAsDp())
                    } else {
                        Modifier.height(5.5f.gridUnitsAsDp())
                    },
                )
                .lightClickable(
                    onClickLabel = if (busy) "Updating $title" else "Toggle $title",
                    role = Role.Switch,
                    onClick = { if (!busy) onToggle(!checked) },
                )
                .padding(end = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightIcon(
                icon = if (checked) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                size = LightBarButtonDefaults.ICON_SIZE_UNITS,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.size(width = 1f.gridUnitsAsDp(), height = 1f.gridUnitsAsDp()))
            LightText(
                text = title,
                variant = LightTextVariant.Heading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lighten = busy,
            )
            if (busy) {
                Spacer(modifier = Modifier.size(width = 1f.gridUnitsAsDp(), height = 1f.gridUnitsAsDp()))
                LightText(
                    text = "Updating…",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                )
            }
        }
        if (description != null) {
            // Deliberately no trailing Spacer here -- unlike the tightened gap above
            // the label, the gap between this description and whatever comes AFTER
            // the whole ToggleRow is still the caller's own explicit Spacer, same as
            // every other row in this file. Adding one here too would double up with
            // that external spacer wherever both are present.
            LightText(
                text = description,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun BooksScreen(
    books: List<Audiobook>,
    loading: Boolean,
    message: String,
    hasDownloads: Boolean,
    libbyConnected: Boolean,
    showListeningStatsButton: Boolean,
    onListeningStats: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onDownloads: () -> Unit,
    onRefresh: () -> Unit,
    onMenu: () -> Unit,
    onOpenBook: (Audiobook) -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showListeningStatsButton) {
                Box(modifier = Modifier.padding(start = 1.5f.gridUnitsAsDp())) {
                    SettingsRow(title = "Listening Stats", onClick = onListeningStats)
                }
            }
            when {
                loading && books.isEmpty() -> BooksStatus("Loading books…", Modifier.weight(1f))
                message.isNotBlank() && books.isEmpty() -> BooksStatus(message, Modifier.weight(1f))
                books.isEmpty() -> BooksStatus("No audiobooks", Modifier.weight(1f))
                else -> {
                    LightLazyScrollView(
                        uniformItemHeightGridUnits = 6.5f,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(
                                start = 1.5f.gridUnitsAsDp(),
                                top = 0.75f.gridUnitsAsDp(),
                            ),
                    ) {
                        items(books, key = { "${it.source}:${it.id}" }) { book ->
                            BookRow(
                                book = book,
                                rssDownloaded = book.source == AudiobookSource.Rss &&
                                    RssDownloadManager.verifiedLocalReference(book) != null,
                                onClick = { onOpenBook(book) },
                            )
                        }
                    }
                }
            }

            LightBottomBar(
                horizontalPaddingUnits = 0.5f,
                // Only include a slot for an item when it's actually relevant --
                // LightBottomBar auto-balances its layout based on how many real
                // items it's given (a fixed 3-slot centered layout for 3, even
                // SpaceBetween for 4/5), so an *omitted* item lets the remaining
                // icons re-center themselves. A *null* entry, by contrast, reserves
                // an invisible slot in place -- which used to matter when the last
                // slot (Now Playing) was conditional; Menu is always present now, so
                // only the Downloads slot still needs the null-vs-omitted distinction.
                items = listOfNotNull(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = onSettings,
                        contentDescription = "Settings",
                    ),
                    // Search opens Libby's own catalog search (see its "Search
                    // Libby" content description) -- it has nothing to offer
                    // without a connected Libby account, so it's hidden the same
                    // way Downloads is hidden when there's nothing to show,
                    // letting the remaining icons re-center rather than leaving a
                    // dead button in the bar.
                    if (libbyConnected) {
                        LightBarButton.LightIcon(
                            icon = LightIcons.SEARCH,
                            onClick = onSearch,
                            contentDescription = "Search Libby",
                        )
                    } else {
                        null
                    },
                    LightBarButton.LightIcon(
                        icon = LightIcons.REFRESH,
                        onClick = onRefresh,
                        contentDescription = "Refresh Books",
                    ),
                    if (hasDownloads) {
                        LightBarButton.LightIcon(
                            icon = LightIcons.DOWNLOADED_ARROW,
                            onClick = onDownloads,
                            contentDescription = "On Device",
                        )
                    } else {
                        null
                    },
                    LightBarButton.LightIcon(
                        icon = LightIcons.MENU,
                        onClick = onMenu,
                        contentDescription = "Libby Tools",
                    ),
                ),
            )
        }
    }
}

@Composable
private fun BooksStatus(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Paragraph,
            align = TextAlign.Center,
            lighten = true,
        )
    }
}

// --- Listening Stats -----------------------------------------------------------
//
// Colorful mode is scoped entirely to this screen's own bars -- it never touches
// LightThemeController (the app-wide monochrome light/dark theme). Default is
// monochrome, matching the LightOS convention of color as an opt-in rather than the
// default; ChartPalette is only consulted at all when the caller passes colorful=true.

private val ChartPalette = listOf(
    Color(0xFFE07A5F),
    Color(0xFF3D5A80),
    Color(0xFF81B29A),
    Color(0xFFD4A373),
    Color(0xFF9B5DE5),
    Color(0xFFEF476F),
)

private data class TimeOfDayBucket(val label: String, val totalMilliseconds: Long)
private data class DayTotal(val label: String, val totalMilliseconds: Long)
private data class GenreTotal(val genre: String, val totalMilliseconds: Long)

private data class ListeningStatsSummary(
    val allTimeMilliseconds: Long,
    val timeOfDay: List<TimeOfDayBucket>,
    val last7Days: List<DayTotal>,
    val thisMonthMilliseconds: Long,
    val thisYearMilliseconds: Long,
    val genreBreakdown: List<GenreTotal>,
    val favoriteGenre: String?,
    val currentStreakDays: Int,
)

/**
 * Pure summarization over the raw session log -- deliberately never re-derives
 * anything from AudiobookProgress (which only remembers a single rolling position,
 * not history). Genre is joined in here at read time via [GenreStore], rather than
 * stored per-session, so a book's genre arriving later (a lazy Libby fetch that
 * hadn't completed yet when a session was logged) is reflected retroactively the
 * next time this screen is opened, without needing to rewrite any log entries.
 */
private fun computeListeningStats(
    sessions: List<ListeningSession>,
    zone: ZoneId = ZoneId.systemDefault(),
): ListeningStatsSummary {
    val allTimeMilliseconds = sessions.sumOf { it.durationMilliseconds }

    val buckets = linkedMapOf("Morning" to 0L, "Afternoon" to 0L, "Evening" to 0L, "Night" to 0L)
    sessions.forEach { session ->
        val hour = Instant.ofEpochMilli(session.startAtMilliseconds).atZone(zone).hour
        val bucketLabel = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
        buckets[bucketLabel] = (buckets[bucketLabel] ?: 0L) + session.durationMilliseconds
    }

    val today = LocalDate.now(zone)
    fun dayLabel(date: LocalDate): String = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "M"
        DayOfWeek.TUESDAY -> "T"
        DayOfWeek.WEDNESDAY -> "W"
        DayOfWeek.THURSDAY -> "T"
        DayOfWeek.FRIDAY -> "F"
        DayOfWeek.SATURDAY -> "S"
        DayOfWeek.SUNDAY -> "S"
    }
    val last7Days = (6 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong())
        val total = sessions
            .filter { Instant.ofEpochMilli(it.startAtMilliseconds).atZone(zone).toLocalDate() == date }
            .sumOf { it.durationMilliseconds }
        DayTotal(label = dayLabel(date), totalMilliseconds = total)
    }

    val thisMonth = YearMonth.from(today)
    val thisMonthMilliseconds = sessions
        .filter { YearMonth.from(Instant.ofEpochMilli(it.startAtMilliseconds).atZone(zone).toLocalDate()) == thisMonth }
        .sumOf { it.durationMilliseconds }
    val thisYearMilliseconds = sessions
        .filter { Instant.ofEpochMilli(it.startAtMilliseconds).atZone(zone).year == today.year }
        .sumOf { it.durationMilliseconds }

    // Each session contributes its FULL duration to every genre its book carries (a
    // book tagged both "Fiction" and "Horror" counts toward both) -- the simplest
    // honest answer to "which genres do you listen to" without needing to invent a
    // fractional split. A book with no known genre yet (never fetched, or a Libby
    // title whose lazy background lookup hasn't run) is bucketed as "Unknown" so
    // that gap is visible rather than silently dropped.
    val genreTotals = linkedMapOf<String, Long>()
    sessions.forEach { session ->
        val genres = GenreStore.genresFor(session.bookId).ifEmpty { listOf("Unknown") }
        genres.forEach { genre ->
            genreTotals[genre] = (genreTotals[genre] ?: 0L) + session.durationMilliseconds
        }
    }
    val genreBreakdown = genreTotals.entries
        .sortedByDescending { it.value }
        .map { GenreTotal(it.key, it.value) }
    val favoriteGenre = genreBreakdown.firstOrNull { it.genre != "Unknown" }?.genre

    // Consecutive days (counting backward from today, or from yesterday if nothing
    // has been logged yet today) with at least one session.
    val sessionDates = sessions
        .map { Instant.ofEpochMilli(it.startAtMilliseconds).atZone(zone).toLocalDate() }
        .toSet()
    var streak = 0
    var cursor = if (today in sessionDates) today else today.minusDays(1)
    while (cursor in sessionDates) {
        streak++
        cursor = cursor.minusDays(1)
    }

    return ListeningStatsSummary(
        allTimeMilliseconds = allTimeMilliseconds,
        timeOfDay = buckets.map { (label, total) -> TimeOfDayBucket(label, total) },
        last7Days = last7Days,
        thisMonthMilliseconds = thisMonthMilliseconds,
        thisYearMilliseconds = thisYearMilliseconds,
        genreBreakdown = genreBreakdown,
        favoriteGenre = favoriteGenre,
        currentStreakDays = streak,
    )
}

private fun formatListeningDuration(milliseconds: Long): String {
    val totalMinutes = milliseconds / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

@Composable
private fun StatBar(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.3f.gridUnitsAsDp()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LightText(
                text = label,
                variant = LightTextVariant.Detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(0.5f.gridUnitsAsDp()))
            LightText(text = valueText, variant = LightTextVariant.Detail, lighten = true)
        }
        Spacer(Modifier.height(0.15f.gridUnitsAsDp()))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.8f.gridUnitsAsDp())
                .background(color.copy(alpha = 0.2f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(color),
            )
        }
    }
}

@Composable
private fun ListeningStatsScreen(
    colorful: Boolean,
    streakEnabled: Boolean,
    onBack: () -> Unit,
) {
    // Sessions only change when the store is appended to (roughly once per finished
    // listen), so re-reading the log fresh each time this screen is opened is enough
    // -- no need for a StateFlow/live-updating subscription here.
    val sessions = remember { ListeningSessionStore.readAllSessions() }
    val summary = remember(sessions) { computeListeningStats(sessions) }

    LivrospeakSurface {
        // Read INSIDE LivrospeakSurface's LightTheme scope, not before it -- outside
        // that scope LightThemeTokens.colors falls back to its CompositionLocal
        // default (always the dark palette) rather than whichever theme the user
        // actually has selected via Invert Colors, which would make the monochrome
        // bars render in the wrong (possibly invisible-on-background) color.
        val palette = if (colorful) {
            ChartPalette
        } else {
            List(ChartPalette.size) { LightThemeTokens.colors.content }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Books",
                ),
                center = LightTopBarCenter.Text("Listening Stats"),
            )

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "No listening tracked yet. Play something, then check back here.",
                        variant = LightTextVariant.Paragraph,
                        align = TextAlign.Center,
                        lighten = true,
                    )
                }
            } else {
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1.5f.gridUnitsAsDp()),
                ) {
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    LightText(
                        text = formatListeningDuration(summary.allTimeMilliseconds),
                        variant = LightTextVariant.Subtitle,
                    )
                    LightText(
                        text = "listened since you turned this on",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )

                    if (streakEnabled) {
                        Spacer(Modifier.height(1.25f.gridUnitsAsDp()))
                        LightText(
                            text = if (summary.currentStreakDays == 1) {
                                "1 day streak"
                            } else {
                                "${summary.currentStreakDays} day streak"
                            },
                            variant = LightTextVariant.Heading,
                        )
                    }

                    Spacer(Modifier.height(1.75f.gridUnitsAsDp()))
                    LightText(text = "Last 7 Days", variant = LightTextVariant.Subheading)
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                    val maxDayMs = (summary.last7Days.maxOfOrNull { it.totalMilliseconds } ?: 0L)
                        .coerceAtLeast(1L)
                    summary.last7Days.forEach { day ->
                        StatBar(
                            label = day.label,
                            valueText = if (day.totalMilliseconds > 0) {
                                formatListeningDuration(day.totalMilliseconds)
                            } else {
                                "—"
                            },
                            fraction = day.totalMilliseconds.toFloat() / maxDayMs.toFloat(),
                            color = palette[0],
                        )
                    }

                    Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            LightText(text = "This Month", variant = LightTextVariant.Detail, lighten = true)
                            LightText(
                                text = formatListeningDuration(summary.thisMonthMilliseconds),
                                variant = LightTextVariant.Heading,
                            )
                        }
                        Column {
                            LightText(text = "This Year", variant = LightTextVariant.Detail, lighten = true)
                            LightText(
                                text = formatListeningDuration(summary.thisYearMilliseconds),
                                variant = LightTextVariant.Heading,
                            )
                        }
                    }

                    Spacer(Modifier.height(1.75f.gridUnitsAsDp()))
                    LightText(text = "Time of Day", variant = LightTextVariant.Subheading)
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                    val maxTimeOfDayMs = (summary.timeOfDay.maxOfOrNull { it.totalMilliseconds } ?: 0L)
                        .coerceAtLeast(1L)
                    summary.timeOfDay.forEachIndexed { index, bucket ->
                        StatBar(
                            label = bucket.label,
                            valueText = if (bucket.totalMilliseconds > 0) {
                                formatListeningDuration(bucket.totalMilliseconds)
                            } else {
                                "—"
                            },
                            fraction = bucket.totalMilliseconds.toFloat() / maxTimeOfDayMs.toFloat(),
                            color = palette[(index + 1) % palette.size],
                        )
                    }

                    Spacer(Modifier.height(1.75f.gridUnitsAsDp()))
                    LightText(text = "Genres", variant = LightTextVariant.Subheading)
                    summary.favoriteGenre?.let { favorite ->
                        Spacer(Modifier.height(0.25f.gridUnitsAsDp()))
                        LightText(
                            text = "Favorite: $favorite",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                    }
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                    val maxGenreMs = (summary.genreBreakdown.maxOfOrNull { it.totalMilliseconds } ?: 0L)
                        .coerceAtLeast(1L)
                    summary.genreBreakdown.take(8).forEachIndexed { index, genre ->
                        StatBar(
                            label = genre.genre,
                            valueText = formatListeningDuration(genre.totalMilliseconds),
                            fraction = genre.totalMilliseconds.toFloat() / maxGenreMs.toFloat(),
                            color = palette[(index + 2) % palette.size],
                        )
                    }
                    Spacer(Modifier.height(2f.gridUnitsAsDp()))
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen(
    books: List<Audiobook>,
    editMode: Boolean,
    onBack: () -> Unit,
    onToggleEdit: () -> Unit,
    onOpenBook: (Audiobook) -> Unit,
    onRemoveBook: (Audiobook) -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Books",
                ),
                center = LightTopBarCenter.Text("On Device"),
                rightButton = LightBarButton.Text(
                    text = if (editMode) "DONE" else "EDIT",
                    onClick = onToggleEdit,
                ),
            )
            if (books.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "Nothing on this device yet",
                        variant = LightTextVariant.Heading,
                        align = TextAlign.Center,
                    )
                }
            } else {
                LightLazyScrollView(
                    uniformItemHeightGridUnits = 7f,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            start = if (editMode) 0.5f.gridUnitsAsDp() else 1.5f.gridUnitsAsDp(),
                            top = 0.75f.gridUnitsAsDp(),
                        ),
                ) {
                    items(books, key = { "download:${it.source}:${it.id}" }) { book ->
                        DownloadRow(
                            book = book,
                            editMode = editMode,
                            onOpen = { onOpenBook(book) },
                            onRemove = { onRemoveBook(book) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    book: Audiobook,
    editMode: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(7f.gridUnitsAsDp())
            .then(
                if (editMode) Modifier else Modifier.lightClickable(
                    onClickLabel = "Open ${book.title}",
                    role = Role.Button,
                    onClick = onOpen,
                ),
            )
            .padding(end = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editMode) {
            Box(
                modifier = Modifier
                    .size(4f.gridUnitsAsDp())
                    .lightClickable(
                        onClickLabel = "Remove ${book.title}",
                        role = Role.Button,
                        onClick = onRemove,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = LightIcons.CLOSE,
                    width = 1.5f,
                    height = 1.5f,
                    contentDescription = "Remove download",
                )
            }
            Spacer(Modifier.size(0.25f.gridUnitsAsDp()))
        }
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = book.title,
                variant = LightTextVariant.Subheading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = book.author.ifBlank { "Unknown Author" },
                variant = LightTextVariant.Detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val source = when (book.source) {
                AudiobookSource.Local -> "Local storage"
                AudiobookSource.Rss -> "RSS feed"
                AudiobookSource.Libby -> ""
            }
            val size = formatDownloadSize(book.fileSizeBytes)
            LightText(
                text = listOf(source, size).filter { it.isNotBlank() }.joinToString(" · "),
                variant = LightTextVariant.Superfine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lighten = true,
            )
        }
    }
}

private fun formatDownloadSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val gibibyte = 1024.0 * 1024.0 * 1024.0
    val mebibyte = 1024.0 * 1024.0
    return if (bytes >= gibibyte) {
        String.format(java.util.Locale.US, "%.1f GB", bytes / gibibyte)
    } else if (bytes < mebibyte) {
        "<1 MB"
    } else {
        "${(bytes / mebibyte).roundToInt()} MB"
    }
}

@Composable
private fun DownloadRemovalConfirmationScreen(
    book: Audiobook,
    message: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to On Device",
                ),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
            ) {
                LightText(
                    text = if (book.source == AudiobookSource.Local) {
                        "Remove this audiobook file from the device?"
                    } else {
                        "Remove this downloaded audiobook?"
                    },
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        lighten = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text("REMOVE", onClick = onConfirm),
                    LightBarButton.Text("CANCEL", onClick = onBack),
                ),
            )
        }
    }
}

private enum class LoanAction { Return, Renew }

@Composable
private fun ManageLoanScreen(
    book: Audiobook,
    message: String,
    busyAction: LoanAction?,
    onBack: () -> Unit,
    onManageTags: () -> Unit,
    onReturnLoan: () -> Unit,
    onRenewLoan: () -> Unit,
) {
    val busy = busyAction != null
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Player",
                ),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = book.title,
                        variant = LightTextVariant.Subheading,
                        align = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lighten = true,
                    )
                }
                Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                LightText(
                    text = when (busyAction) {
                        LoanAction.Return -> "Returning this loan…"
                        LoanAction.Renew -> "Renewing this loan…"
                        null -> "Manage this loan"
                    },
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        lighten = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            LightBottomBar(
                // Icon-text-icon with TAG in the middle -- this is the one 3-item
                // arrangement LightBottomBar already spaces evenly on its own (see
                // isMixedIconTextIconLayout), so putting the single text item in the
                // middle slot gets the "evened out" spacing for free instead of
                // needing a new layout case. This slot used to be a dismiss "CANCEL"
                // button, dropped as redundant once the Back arrow on LightTopBar
                // covered that -- Tag fills the freed slot since it's a real action
                // this screen didn't have a home for otherwise. The busy heading above
                // ("Renewing this loan…" / "Returning this loan…") already carries
                // which action is in flight, so the icons don't need their own busy
                // state -- both just go inert via the same onClick-becomes-a-no-op
                // pattern the text button uses.
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.RENEW,
                        onClick = onRenewLoan.takeIf { !busy } ?: {},
                        contentDescription = "Renew Loan",
                    ),
                    LightBarButton.Text("TAG", onClick = onManageTags.takeIf { !busy } ?: {}),
                    LightBarButton.LightIcon(
                        icon = LightIcons.RETURN,
                        onClick = onReturnLoan.takeIf { !busy } ?: {},
                        contentDescription = "Return Loan",
                    ),
                ),
            )
        }
    }
}

@Composable
private fun BookRow(book: Audiobook, rssDownloaded: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.5f.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = "Play ${book.title}",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                end = 1f.gridUnitsAsDp(),
                top = 0.25f.gridUnitsAsDp(),
                bottom = 0.25f.gridUnitsAsDp(),
            ),
        verticalArrangement = Arrangement.Top,
    ) {
        LightText(
            text = book.title,
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = book.author.ifBlank { "Unknown Author" },
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val progress = book.progressPercent.takeIf { it > 0 }?.let { "$it%" } ?: "Not started"
        val sourceStatus = when (book.source) {
            AudiobookSource.Libby -> book.dueText
            AudiobookSource.Local -> "Local storage"
            AudiobookSource.Rss -> if (rssDownloaded) "Downloaded" else "Not downloaded"
        }
        val status = listOf(progress, sourceStatus)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        LightText(
            text = status,
            variant = LightTextVariant.Superfine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lighten = true,
        )
    }
}

/**
 * Search Libby's catalog and borrow/hold audiobooks directly, without leaving Livrospeak.
 *
 * Search text entry reuses the same LP3 keyboard flow as [AddRssFeedScreen] rather than
 * a dedicated search field component, since that's the only text-input pattern already
 * proven out in this codebase.
 */
@Composable
private fun LibbySearchScreen(
    onBack: () -> Unit,
    onBorrowed: () -> Unit,
    onManageTags: (titleId: String, titleName: String, resync: suspend () -> Boolean) -> Unit,
) {
    val sessionReady by LibbyConnectCoordinator.sessionState.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<LibbySearchResult>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<LibbySearchResult?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun runSearch(term: String) {
        searching = true
        message = ""
        results = emptyList()
        // Every linked library card (see LibbyWebPlayer.LIBBY_LIBRARIES) gets its
        // own page-by-page scrape and results are merged -- see navigateToSearch's
        // KDoc for why a title only available (or ranked differently) at one
        // linked library would otherwise be invisible if only a different one is
        // ever searched. This means a search now takes roughly (number of
        // libraries) times as long as before -- worth it since the alternative is
        // silently missing books a specific card actually has.
        val collected = linkedMapOf<String, LibbySearchResult>()
        for (library in LibbyWebPlayer.LIBBY_LIBRARIES) {
            var page = 1
            while (page <= MAX_LIBBY_SEARCH_PAGES) {
                LibbyWebPlayer.navigateToSearch(library, term, page)
                // Libby's search results render client-side after the route loads; poll
                // a few times the same way the shelf-hydration loop in
                // PlayerDebugScreen() does.
                var found = emptyList<LibbySearchResult>()
                var attempts = 0
                while (attempts < 10 && found.isEmpty()) {
                    delay(400)
                    found = suspendCancellableCoroutine { continuation ->
                        LibbyWebPlayer.scrapeSearchResults { list ->
                            if (continuation.isActive) continuation.resume(list)
                        }
                    }
                    attempts++
                }
                if (found.isEmpty()) break // no more pages of results from this library
                // Keyed by library+titleId, not just titleId: OverDrive's catalog is
                // shared, so the exact same title can legitimately turn up under two
                // different linked libraries with different availability at each.
                found.forEach {
                    collected["${library.id}:${it.titleId}"] = it.copy(
                        page = page,
                        libraryId = library.id,
                        libraryName = library.displayName,
                    )
                }
                results = collected.values.toList() // show results as they come in
                page++
            }
        }
        hasSearched = true
        searching = false
        if (collected.isEmpty()) {
            message = "No audiobooks found for \"$term\"."
        }
    }

    fun performAction(result: LibbySearchResult) {
        actionBusy = true
        message = ""
        scope.launch {
            val library = LibbyWebPlayer.LIBBY_LIBRARIES.find { it.id == result.libraryId }
            if (library == null) {
                actionBusy = false
                message = "Couldn't tell which library \"${result.title}\" came from."
                return@launch
            }
            // Search now queries every configured library and merges pages from
            // each (see runSearch), so the WebView may have moved on to a
            // completely different library's pages -- not just a later page of the
            // SAME library -- by the time the user taps this result. Re-navigate to
            // the exact library + page it was scraped from and confirm the tile is
            // actually there before trying to click anything on it -- otherwise
            // borrowOrHoldSearchResult would be searching the wrong page's DOM for
            // this titleId and report a false "couldn't find" failure.
            LibbyWebPlayer.navigateToSearch(library, query.trim(), result.page)
            var onCorrectPage = false
            for (attempt in 0 until 10) {
                delay(400)
                val stillThere = suspendCancellableCoroutine<Boolean> { continuation ->
                    LibbyWebPlayer.scrapeSearchResults { list ->
                        if (continuation.isActive) continuation.resume(list.any { it.titleId == result.titleId })
                    }
                }
                if (stillThere) {
                    onCorrectPage = true
                    break
                }
            }

            // borrowOrHoldSearchResult now drives Libby's real, multi-step confirm flow
            // through to completion (click the tile's button, wait for the review
            // screen, click ITS OWN confirm button, then wait for Libby to actually
            // report success) -- see its KDoc for why a single click was never enough
            // to reliably place a loan/hold.
            val (confirmed, detail) = if (onCorrectPage) {
                LibbyWebPlayer.borrowOrHoldSearchResult(result.titleId)
            } else {
                false to "couldn't reload this result's search page"
            }
            actionBusy = false
            pendingAction = null
            if (confirmed) {
                message = "\"${detail.ifBlank { "Borrow" }}\" confirmed for \"${result.title}\"."
                delay(800)
                onBorrowed()
            } else {
                message = "Couldn't complete the borrow/hold for \"${result.title}\" ($detail). " +
                    "It may already be on your shelf, or Libby's page changed."
            }
        }
    }

    // Re-navigates to the exact library + page [result] was scraped from and
    // confirms its tile is actually there, the same re-navigate-then-confirm
    // step performAction (above) already does before Borrow/Hold -- a search
    // queries every linked library and merges pages from each, so by the time
    // someone acts on a result the shared hidden WebView has likely moved on to
    // a completely different library's page. That mismatch was what actually
    // produced the "no-tile" diagnostic on every single search result -- not a
    // wrong selector, just the wrong page loaded when openSavePanelForTitle went
    // looking. Exposed as its own function (not just inlined in
    // requestManageTags below) so it can be handed to openManageTags as
    // [manageTagsResync] and re-run before every later toggle/create too, not
    // just this first open -- a real "no-button" failure recurred specifically
    // on tag CREATION even after the initial open had already succeeded, since
    // real time (reading tags, typing a name) is enough for the WebView to
    // drift again.
    suspend fun resyncSearchResultPage(result: LibbySearchResult): Boolean {
        val library = LibbyWebPlayer.LIBBY_LIBRARIES.find { it.id == result.libraryId } ?: return false
        LibbyWebPlayer.navigateToSearch(library, query.trim(), result.page)
        for (attempt in 0 until 10) {
            delay(400)
            val stillThere = suspendCancellableCoroutine<Boolean> { continuation ->
                LibbyWebPlayer.scrapeSearchResults { list ->
                    if (continuation.isActive) continuation.resume(list.any { it.titleId == result.titleId })
                }
            }
            if (stillThere) return true
        }
        return false
    }

    fun requestManageTags(result: LibbySearchResult) {
        actionBusy = true
        message = ""
        scope.launch {
            val onCorrectPage = resyncSearchResultPage(result)
            actionBusy = false
            if (onCorrectPage) {
                onManageTags(result.titleId, result.title) { resyncSearchResultPage(result) }
            } else {
                message = "Couldn't reopen \"${result.title}\" to manage its tags."
            }
        }
    }

    // `searching` must short-circuit this to true on its own: once `hasSearched`
    // is true from a prior search, re-running a search sets `searching = true`
    // and clears `results` before the new results arrive, and without this
    // clause the formula fell through to the results-list branch below with
    // nothing in it -- a blank screen under the top bar for the whole search,
    // which is the "page goes black" bug.
    val showQueryEditor = sessionReady == LibbySessionState.Connected &&
        pendingAction == null &&
        (searching || !hasSearched || results.isEmpty())

    LivrospeakSurface {
        if (showQueryEditor) {
            // LightUrlInputEditor renders its own full-screen top bar + LP3 keyboard,
            // so it is the entire screen here rather than nested under another one
            // (same usage as AddRssFeedScreen).
            LightUrlInputEditor(
                value = query,
                onValueChange = { query = it },
                message = when {
                    searching -> "Searching…"
                    message.isNotBlank() -> message
                    else -> ""
                },
                submitting = searching,
                onBack = onBack,
                onDone = {
                    if (query.isNotBlank()) {
                        scope.launch { runSearch(query.trim()) }
                    }
                },
                title = "Search Libby",
                doneLabel = "SEARCH",
                submittingLabel = "SEARCHING…",
                keyboardKey = "LivrospeakLibbySearchKeyboard",
                backContentDescription = "Back to Books",
                underline = false,
            )
        } else {
            val topBarBack: () -> Unit = when {
                pendingAction != null -> {
                    { pendingAction = null }
                }
                sessionReady == LibbySessionState.Connected -> {
                    {
                        hasSearched = false
                        message = ""
                    }
                }
                else -> onBack
            }
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = topBarBack,
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text("Search Libby"),
                )

                when {
                    sessionReady != LibbySessionState.Connected -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 2f.gridUnitsAsDp()),
                            contentAlignment = Alignment.Center,
                        ) {
                            LightText(
                                text = "Connect Libby in Settings to search and borrow audiobooks.",
                                variant = LightTextVariant.Paragraph,
                                align = TextAlign.Center,
                            )
                        }
                    }

                    pendingAction != null -> {
                        val result = pendingAction!!
                        val actionLabel = when (result.availability) {
                            LibbyAvailability.Wait -> "Place a Hold"
                            else -> "Borrow"
                        }
                        // Short, button-only label -- "PLACE A HOLD" fits fine in a
                        // sentence but truncates in the bottom bar's fixed-width slot
                        // (same class of issue RETURN/RENEW hit as "RETURNING"/
                        // "RENEWING" before being shortened), so the bar gets its own
                        // compact word instead of reusing actionLabel.uppercase().
                        val buttonLabel = when (result.availability) {
                            LibbyAvailability.Wait -> "HOLD"
                            else -> "BORROW"
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 2f.gridUnitsAsDp()),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            LightText(
                                text = result.title,
                                variant = LightTextVariant.Heading,
                            )
                            LightText(
                                text = result.author.ifBlank { "Unknown Author" },
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                            if (result.libraryName.isNotBlank()) {
                                LightText(
                                    text = result.libraryName,
                                    variant = LightTextVariant.Superfine,
                                    lighten = true,
                                )
                            }
                            Spacer(Modifier.height(1f.gridUnitsAsDp()))
                            LightText(
                                text = if (actionBusy) "Working…" else "Confirm: $actionLabel this title?",
                                variant = LightTextVariant.Paragraph,
                            )
                            if (message.isNotBlank()) {
                                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                                LightText(message, variant = LightTextVariant.Detail, lighten = true)
                            }
                        }
                        LightBottomBar(
                            // Used to also have a "CANCEL" button here, dropped as
                            // redundant -- it did the exact same thing as the Back
                            // arrow on LightTopBar above (both just clear
                            // pendingAction), the same situation already resolved
                            // this way on ManageLoanScreen.
                            items = listOf(
                                LightBarButton.Text(
                                    text = if (actionBusy) "…" else buttonLabel,
                                    onClick = if (actionBusy) ({}) else ({ performAction(result) }),
                                ),
                                LightBarButton.Text(
                                    text = "TAG",
                                    onClick = if (actionBusy) {
                                        {}
                                    } else {
                                        { requestManageTags(result) }
                                    },
                                ),
                            ),
                        )
                    }

                    else -> {
                        // Results list (query editor above handles the empty/loading cases).
                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(start = 1.5f.gridUnitsAsDp()),
                        ) {
                            results.forEach { result ->
                                SearchResultRow(
                                    result = result,
                                    onClick = { pendingAction = result },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: LibbySearchResult, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.5f.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = "View ${result.title}",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                end = 1f.gridUnitsAsDp(),
                top = 0.25f.gridUnitsAsDp(),
                bottom = 0.25f.gridUnitsAsDp(),
            ),
        verticalArrangement = Arrangement.Top,
    ) {
        LightText(
            text = result.title,
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = result.author.ifBlank { "Unknown Author" },
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val status = when (result.availability) {
            LibbyAvailability.Available -> "Available to borrow"
            LibbyAvailability.Wait -> "Wait list"
            LibbyAvailability.Owned -> "Already on your shelf"
            LibbyAvailability.Unknown -> result.availabilityText.ifBlank { "Tap to view" }
        }
        // Search now merges results across every linked library (see runSearch),
        // so the library name is shown alongside status -- otherwise there'd be no
        // way to tell which of your cards a given result actually belongs to.
        val statusWithLibrary = if (result.libraryName.isNotBlank()) {
            "$status · ${result.libraryName}"
        } else {
            status
        }
        LightText(
            text = statusWithLibrary,
            variant = LightTextVariant.Superfine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lighten = true,
        )
    }
}

@Composable
private fun PlayerScreen(
    state: PlayerState,
    book: Audiobook?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onBack15: () -> Unit,
    onForward15: () -> Unit,
    onSeekTo: (positionMilliseconds: Long) -> Unit,
    onSetSpeed: (Double) -> Unit,
    downloadStatus: RssDownloadStatus?,
    onDownload: (() -> Unit)?,
    statusText: String,
    onBooks: () -> Unit,
    onManageLoan: (() -> Unit)? = null,
) {
    var showSpeedPicker by remember { mutableStateOf(false) }
    var requestedSpeed by remember { mutableStateOf<Double?>(null) }
    var scrubProgress by remember { mutableStateOf<Float?>(null) }
    var pendingSeekMilliseconds by remember { mutableStateOf<Long?>(null) }
    val title = book?.title?.takeIf { it.isNotBlank() }
        ?: state.title.takeIf { it.isNotBlank() && it != "Libby" }
        ?: "Audiobook"
    val authoritativePositionSeconds = when {
        state.positionSeconds > 0 || state.readiness == PlayerReadiness.Ready -> state.positionSeconds
        book?.source == AudiobookSource.Rss && book.positionMilliseconds > 0 ->
            book.positionMilliseconds / 1000.0
        else -> state.positionSeconds
    }
    val liveProgress = if (state.durationSeconds > 0) {
        (authoritativePositionSeconds / state.durationSeconds).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val hasTiming = state.durationSeconds > 0
    val pendingProgress = pendingSeekMilliseconds?.let { pending ->
        if (hasTiming) (pending / (state.durationSeconds * 1000.0)).toFloat().coerceIn(0f, 1f)
        else null
    }
    val displayedProgress = scrubProgress ?: pendingProgress ?: liveProgress
    val displayedPositionSeconds = when {
        scrubProgress != null -> scrubProgress!!.toDouble() * state.durationSeconds
        pendingSeekMilliseconds != null -> pendingSeekMilliseconds!! / 1000.0
        else -> authoritativePositionSeconds
    }
    val displayedSpeed = requestedSpeed ?: state.playbackSpeed

    LaunchedEffect(state.playbackSpeed, requestedSpeed) {
        val requested = requestedSpeed ?: return@LaunchedEffect
        if (kotlin.math.abs(state.playbackSpeed - requested) < 0.01) {
            requestedSpeed = null
        }
    }

    LaunchedEffect(state.positionSeconds, pendingSeekMilliseconds) {
        val pending = pendingSeekMilliseconds ?: return@LaunchedEffect
        val actualMilliseconds = (state.positionSeconds * 1000.0).roundToLong()
        if (kotlin.math.abs(actualMilliseconds - pending) <= 2_000L) {
            pendingSeekMilliseconds = null
        }
    }

    LaunchedEffect(pendingSeekMilliseconds) {
        val pending = pendingSeekMilliseconds ?: return@LaunchedEffect
        delay(3_000)
        if (pendingSeekMilliseconds == pending) pendingSeekMilliseconds = null
    }

    LivrospeakSurface {
        if (showSpeedPicker) {
            SpeedPicker(
                currentSpeed = displayedSpeed,
                onSelect = { speed ->
                    requestedSpeed = speed
                    onSetSpeed(speed)
                    showSpeedPicker = false
                },
                onClose = { showSpeedPicker = false },
            )
            return@LivrospeakSurface
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBooks,
                    contentDescription = "Back to Books",
                ),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                val author = book?.author.orEmpty()
                if (book?.source != AudiobookSource.Rss || author.isNotBlank()) {
                    LightText(
                        text = author,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                }
                LightText(
                    text = title,
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                LightText(
                    text = when {
                        hasTiming -> formatPlaybackTime(state.durationSeconds)
                        state.diagnostic == "This audiobook could not be played." -> state.diagnostic
                        else -> "--:--"
                    },
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(0.25f.gridUnitsAsDp()))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.25f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    if (statusText.isNotBlank()) {
                        LightText(
                            text = statusText,
                            variant = LightTextVariant.Superfine,
                            align = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            lighten = true,
                        )
                    }
                }

                Spacer(
                    Modifier.height(
                        if (book?.source == AudiobookSource.Rss) {
                            0.75f.gridUnitsAsDp()
                        } else {
                            2.5f.gridUnitsAsDp()
                        },
                    ),
                )
                PlaybackProgress(
                    progress = displayedProgress,
                    enabled = hasTiming && state.readiness == PlayerReadiness.Ready,
                    onScrub = { scrubProgress = it },
                    onScrubCancelled = { scrubProgress = null },
                    onSeek = { fraction ->
                        val targetMilliseconds =
                            (state.durationSeconds * 1000.0 * fraction).roundToLong()
                                .coerceIn(0L, (state.durationSeconds * 1000.0).roundToLong())
                        scrubProgress = null
                        pendingSeekMilliseconds = targetMilliseconds
                        onSeekTo(targetMilliseconds)
                    },
                )

                if (book?.source == AudiobookSource.Rss) {
                    Spacer(Modifier.height(0.25f.gridUnitsAsDp()))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerIconAction(
                        icon = LightIcons.SKIP_BACKWARD_FIFTEEN,
                        description = "Rewind 15 seconds",
                        iconWidth = 3f,
                        iconHeight = 3.25f,
                        enabled = state.readiness == PlayerReadiness.Ready,
                        onClick = onBack15,
                    )
                    PlayerIconAction(
                        icon = if (state.isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                        description = if (state.isPlaying) "Pause" else "Play",
                        iconWidth = 2.5f,
                        iconHeight = 2.5f,
                        touchSize = 6f,
                        enabled = state.readiness == PlayerReadiness.Ready,
                        onClick = {
                            if (state.isPlaying) onPause() else onPlay()
                        },
                    )
                    PlayerIconAction(
                        icon = LightIcons.SKIP_FORWARD_FIFTEEN,
                        description = "Forward 15 seconds",
                        iconWidth = 3f,
                        iconHeight = 3.25f,
                        enabled = state.readiness == PlayerReadiness.Ready,
                        onClick = onForward15,
                    )
                }
                Spacer(
                    Modifier.height(
                        if (book?.source == AudiobookSource.Rss) {
                            0.25f.gridUnitsAsDp()
                        } else {
                            1f.gridUnitsAsDp()
                        },
                    ),
                )
                LightText(
                    text = if (hasTiming || authoritativePositionSeconds > 0) {
                        formatPlaybackTime(displayedPositionSeconds)
                    } else {
                        "--:--"
                    },
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
            }

            LightBottomBar(
                items = listOfNotNull(
                    downloadStatus?.let { download ->
                        when (download) {
                            RssDownloadStatus.Downloading -> LightBarButton.Text("Downloading", onClick = {})
                            RssDownloadStatus.Downloaded -> LightBarButton.LightIcon(
                                icon = LightIcons.DOWNLOADED_ARROW,
                                onClick = {},
                                contentDescription = "Downloaded",
                            )
                            RssDownloadStatus.NotDownloaded, RssDownloadStatus.Failed ->
                                LightBarButton.LightIcon(
                                    icon = LightIcons.DOWNLOAD_ARROW,
                                    onClick = onDownload ?: {},
                                    contentDescription = "Download",
                                )
                        }
                    },
                    LightBarButton.Text(
                        "${trimSpeed(displayedSpeed)}×",
                        onClick = { showSpeedPicker = true },
                    ),
                    onManageLoan?.let {
                        LightBarButton.LightIcon(
                            icon = LightIcons.LIST,
                            onClick = it,
                            contentDescription = "Manage Loan",
                        )
                    },
                ),
            )
        }
    }
}

@Composable
private fun PlaybackProgress(
    progress: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubCancelled: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    var trackWidthPixels by remember { mutableStateOf(0) }
    fun progressAt(offset: Offset): Float =
        if (trackWidthPixels > 0) (offset.x / trackWidthPixels).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3f.gridUnitsAsDp())
            .onSizeChanged { trackWidthPixels = it.width }
            .pointerInput(enabled, trackWidthPixels) {
                if (!enabled || trackWidthPixels <= 0) return@pointerInput
                detectTapGestures { offset -> onSeek(progressAt(offset)) }
            }
            .pointerInput(enabled, trackWidthPixels) {
                if (!enabled || trackWidthPixels <= 0) return@pointerInput
                var proposedProgress = progress
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        proposedProgress = progressAt(offset)
                        onScrub(proposedProgress)
                    },
                    onDragCancel = onScrubCancelled,
                    onDragEnd = { onSeek(proposedProgress) },
                ) { change, _ ->
                    proposedProgress = progressAt(change.position)
                    onScrub(proposedProgress)
                    change.consume()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .height(0.08f.gridUnitsAsDp())
                .fillMaxWidth()
                .background(LightThemeTokens.colors.contentSecondary),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(0.35f.gridUnitsAsDp())
                .background(LightThemeTokens.colors.content),
        )
    }
}

@Composable
private fun PlayerIconAction(
    icon: com.tyshi00.livrospeak.ui.LightIconConfiguration,
    description: String,
    iconWidth: Float = 2f,
    iconHeight: Float = 2f,
    touchSize: Float = 5f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(touchSize.gridUnitsAsDp())
            .then(
                if (enabled) {
                    Modifier.lightClickable(
                        onClickLabel = description,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(
            icon = icon,
            width = iconWidth,
            height = iconHeight,
            contentDescription = description,
        )
    }
}

@Composable
private fun SpeedPicker(
    currentSpeed: Double,
    onSelect: (Double) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onClose,
                contentDescription = "Back to Player",
            ),
            center = LightTopBarCenter.Text("Playback Speed"),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 2f.gridUnitsAsDp()),
        ) {
            Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
            listOf(1.0, 1.25, 1.5, 1.75, 2.0).forEach { speed ->
                val isSelected = kotlin.math.abs(currentSpeed - speed) < 0.01
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.5f.gridUnitsAsDp())
                        .semantics { selected = isSelected }
                        .lightClickable(
                            onClickLabel = "Set speed to ${trimSpeed(speed)} times",
                            role = Role.RadioButton,
                        ) { onSelect(speed) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    LightText(
                        text = "${trimSpeed(speed)}×",
                        variant = LightTextVariant.Heading,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibbySourceScreen(
    sessionState: LibbySessionState,
    hasLibraryCardPin: Boolean,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onResetLibraryCardPin: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Settings",
                ),
                center = LightTopBarCenter.Text("Libby"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                // Right after launch (including every fresh install while testing),
                // the real session takes a few seconds to verify -- see
                // LibbyConnectCoordinator.refreshSessionState(), which polls up to
                // 12 times. Collapsing that "still checking" moment into the same
                // "not connected" state as a genuine disconnect was what made this
                // screen show "Connect to Libby" right after you'd already linked
                // your account, until you backed out and back in gave the check
                // enough time to finish -- it looked like the connection hadn't
                // taken even though it had. Showing a distinct in-between state
                // here means there's no more misleading moment to wait out.
                when (sessionState) {
                    LibbySessionState.Checking -> SourceTextRow("Checking connection…")
                    LibbySessionState.Connected -> {
                        SourceTextRow("Connected")
                        SettingsRow(title = "Disconnect", onClick = onDisconnect)
                        // Only shown once a PIN actually exists -- otherwise
                        // there's nothing to reset, and the Library Cards
                        // screen's own first-time setup already handles
                        // creating one. This is the PIN's only reset path now
                        // (see LibraryCardPinStore's doc comment): reachable,
                        // but not from the lock screen itself, so idle wrong
                        // guesses alone can't trigger it.
                        if (hasLibraryCardPin) {
                            SettingsRow(
                                title = "Reset Library Card PIN",
                                onClick = onResetLibraryCardPin,
                            )
                        }
                    }
                    LibbySessionState.Disconnected -> {
                        // Connecting is a two-step round trip: tapping "Connect to Libby"
                        // opens the setup code, which gets entered over on libbyapp.com/
                        // Libby's own app on another device -- Livrospeak has no way to
                        // know the moment that finishes, so the user has to come back here
                        // and tap it again to actually pick up the now-linked session.
                        // Skipping that explanation was what made a successful connection
                        // look like it hadn't worked (see the Checking-state comment below).
                        LightText(
                            text = "Once the code for Libby has been input successfully, " +
                                "please tap \"Connect to Libby\" on this device again to " +
                                "finalize connection.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        SettingsRow(title = "Connect to Libby", onClick = onConnect)
                    }
                }
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                // Every search queries each of these in turn and merges the results,
                // so this list also doubles as an honest disclaimer: a search takes
                // roughly this many times as long as a single-library search would,
                // and a library card that isn't listed here isn't searched at all --
                // adding one currently means sending a real search URL for it (the
                // same way Cobb County and Sequoyah Regional were set up) rather
                // than adding it from this screen. The library names below come
                // straight from LIBBY_LIBRARIES, never hardcoded in this string --
                // whoever builds this app sees only the library cards actually
                // configured for their own install, not any one person's libraries.
                LightText(
                    text = "Searches are linked to these libraries: " +
                        LibbyWebPlayer.LIBBY_LIBRARIES.joinToString { it.displayName } + ".",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
        }
    }
}

@Composable
private fun LibbyConnectScreen(
    state: LibbyConnectState,
    onBack: () -> Unit,
    onNewCode: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val view = LocalView.current
    val keepAwake = state is LibbyConnectState.Code
    DisposableEffect(view, keepAwake) {
        val previous = view.keepScreenOn
        if (keepAwake) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Libby settings",
                ),
                center = LightTopBarCenter.Text("Connect to Libby"),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
                when (state) {
                    LibbyConnectState.Idle,
                    LibbyConnectState.Loading -> {
                        LightText(
                            text = "Preparing code…",
                            variant = LightTextVariant.Paragraph,
                            lighten = true,
                        )
                    }

                    is LibbyConnectState.Code -> {
                        LightText(
                            text = "On your other device:",
                            variant = LightTextVariant.Detail,
                        )
                        Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                        LightText(
                            text = "Menu → Copy To Another Device",
                            variant = LightTextVariant.Paragraph,
                        )
                        Spacer(Modifier.height(2f.gridUnitsAsDp()))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            state.digits.forEach { digit ->
                                LightText(
                                    text = digit.toString(),
                                    variant = LightTextVariant.Heading,
                                    monospace = true,
                                )
                            }
                        }
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        LightText(
                            text = "${state.secondsRemaining} seconds remaining",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                    }

                    LibbyConnectState.Expired -> {
                        LightText(text = "Code expired", variant = LightTextVariant.Heading)
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        SettingsRow(title = "New Code", onClick = onNewCode)
                    }

                    LibbyConnectState.Connected -> {
                        LightText(text = "Connected", variant = LightTextVariant.Heading)
                    }

                    is LibbyConnectState.Error -> {
                        LightText(
                            text = state.userMessage,
                            variant = LightTextVariant.Paragraph,
                        )
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        SettingsRow(title = "Retry", onClick = onRetry)
                        SettingsRow(title = "Cancel", onClick = onCancel)
                    }
                }
            }
        }
    }
}

@Composable
private fun DisconnectLibbyScreen(
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Libby settings",
                ),
                center = LightTopBarCenter.Text("Disconnect Libby"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Disconnect Libby from Livrospeak?",
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(end = 1.5f.gridUnitsAsDp()),
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                SettingsRow(title = "Disconnect", onClick = onDisconnect)
                SettingsRow(title = "Cancel", onClick = onCancel)
            }
        }
    }
}

@Composable
private fun ResetLibraryCardPinScreen(
    onBack: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Libby settings",
                ),
                center = LightTopBarCenter.Text("Reset PIN"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Reset the Library Card PIN? The next time Library Cards " +
                        "is opened, it will ask for a new one to be set.",
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(end = 1.5f.gridUnitsAsDp()),
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                SettingsRow(title = "Reset PIN", onClick = onReset)
                SettingsRow(title = "Cancel", onClick = onCancel)
            }
        }
    }
}

@Composable
private fun SourceTextRow(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.5f.gridUnitsAsDp()),
        contentAlignment = Alignment.CenterStart,
    ) {
        LightText(text = title, variant = LightTextVariant.Heading)
    }
}

@Composable
private fun LocalBooksScreen(
    result: LocalScanResult?,
    scanning: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Settings",
                ),
                center = LightTopBarCenter.Text("Local Books"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                LightText(
                    "Place your audiobook files directly into the Light Phone III/Audiobooks folder (create one if none exists).",
                    variant = LightTextVariant.Paragraph,
                )
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                LightText(
                    "MP3 files are supported reliably on the Light Phone III. " +
                        "M4B/M4A files may not play on this device.",
                    variant = LightTextVariant.Paragraph,
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))

                val status = if (scanning) {
                    "Scanning…"
                } else when (result) {
                    null -> ""
                    LocalScanResult.PermissionRequired -> "Livrospeak needs permission to read audio files."
                    LocalScanResult.FolderMissing -> "No Audiobooks folder found."
                    LocalScanResult.Empty -> "No audiobook files found."
                    is LocalScanResult.Success -> "${result.books.size} books found"
                }
                if (status.isNotBlank()) {
                    LightText(status, variant = LightTextVariant.Detail, lighten = true)
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                }
                SettingsRow(
                    title = if (result == null) "Scan for Books" else "Scan Again",
                    onClick = if (scanning) ({}) else onScan,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: LivrospeakSettings,
    onInvertColorsChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenAudiobookSources: () -> Unit,
    onOpenLibby: () -> Unit,
    onOpenLocalBooks: () -> Unit,
    onOpenRssFeeds: () -> Unit,
    onListeningStatsEnabledChange: (Boolean) -> Unit,
    onListeningStatsColorfulChange: (Boolean) -> Unit,
    onListeningStreakEnabledChange: (Boolean) -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("Settings"),
            )

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                ToggleRow(
                    title = "Invert Colors",
                    checked = settings.invertColors,
                    onToggle = onInvertColorsChange,
                )
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                ToggleRow(
                    title = "Listening Stats",
                    checked = settings.listeningStatsEnabled,
                    onToggle = onListeningStatsEnabledChange,
                    description = "Tracks how much you listen and when. A \"Listening " +
                        "Stats\" button will appear at the top of your shelf."
                        .takeIf { settings.listeningStatsEnabled },
                )
                if (settings.listeningStatsEnabled) {
                    Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                    ToggleRow(
                        title = "Colorful Charts",
                        checked = settings.listeningStatsColorful,
                        onToggle = onListeningStatsColorfulChange,
                    )
                    Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                    ToggleRow(
                        title = "Listening Streak",
                        checked = settings.listeningStreakEnabled,
                        onToggle = onListeningStreakEnabledChange,
                        description = "Shows how many days in a row you've listened."
                            .takeIf { settings.listeningStreakEnabled },
                    )
                }
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                SettingsRow(
                    title = "Choose Your Audiobook Source",
                    onClick = onOpenAudiobookSources,
                    wrap = true,
                )
                if (settings.showLibbyBooks) {
                    SettingsRow(title = "Libby", onClick = onOpenLibby)
                }
                if (settings.showLocalBooks) {
                    SettingsRow(title = "Local Books", onClick = onOpenLocalBooks)
                }
                if (RSS_FEED_FEATURE_ENABLED && settings.showRssBooks) {
                    SettingsRow(title = "RSS Feeds", onClick = onOpenRssFeeds)
                }
            }
        }
    }
}

@Composable
private fun AudiobookSourceSettingsScreen(
    settings: LivrospeakSettings,
    onShowLibbyBooksChange: (Boolean) -> Unit,
    onShowLocalBooksChange: (Boolean) -> Unit,
    onShowRssBooksChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Settings",
                ),
                center = LightTopBarCenter.Text("Audiobook Source"),
            )

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                ToggleRow(
                    title = "Show Libby Books",
                    checked = settings.showLibbyBooks,
                    onToggle = onShowLibbyBooksChange,
                )
                ToggleRow(
                    title = "Show Local Books",
                    checked = settings.showLocalBooks,
                    onToggle = onShowLocalBooksChange,
                )
                if (RSS_FEED_FEATURE_ENABLED) {
                    ToggleRow(
                        title = "Show RSS Books",
                        checked = settings.showRssBooks,
                        onToggle = onShowRssBooksChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun RssFeedsScreen(
    feeds: List<RssFeed>,
    onBack: () -> Unit,
    onAddFeed: () -> Unit,
    onOpenFeed: (RssFeed) -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Settings",
                ),
                center = LightTopBarCenter.Text("RSS Feeds"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                SettingsRow(title = "Add Feed", onClick = onAddFeed)
                feeds.forEach { feed ->
                    SettingsRow(
                        title = RssFeedRepository.displayName(feed),
                        onClick = { onOpenFeed(feed) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddRssFeedScreen(
    onBack: () -> Unit,
    onSubmit: suspend (String) -> String?,
) {
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LivrospeakSurface {
        LightUrlInputEditor(
            value = url,
            onValueChange = { url = it },
            message = message,
            submitting = submitting,
            onBack = onBack,
            onDone = {
                submitting = true
                message = ""
                scope.launch {
                    message = onSubmit(url.trim()).orEmpty()
                    submitting = false
                }
            },
        )
    }
}

@Composable
private fun RssFeedDetailScreen(
    feed: RssFeed,
    refreshing: Boolean,
    message: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to RSS Feeds",
                ),
                center = LightTopBarCenter.Text(RssFeedRepository.displayName(feed)),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                if (message.isNotBlank()) {
                    LightText(message, variant = LightTextVariant.Detail, lighten = true)
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                }
                SettingsRow(
                    title = if (refreshing) "Refreshing…" else "Refresh",
                    onClick = if (refreshing) ({}) else onRefresh,
                )
                SettingsRow(title = "Remove Feed", onClick = onRemove)
            }
        }
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    onClick: () -> Unit = {},
    wrap: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (wrap) {
                    Modifier
                        .heightIn(min = 5.5f.gridUnitsAsDp())
                        .padding(vertical = 0.5f.gridUnitsAsDp())
                } else {
                    Modifier.height(5.5f.gridUnitsAsDp())
                },
            )
            .lightClickable(
                // Was "Open $title settings" -- fine while this row only ever lived on
                // the Settings screen, but it's now reused by LibbyToolsScreen too
                // ("Open Holds settings" reads oddly for a row that isn't a settings
                // toggle), so the label is title-only and still reads naturally in
                // both places.
                onClickLabel = "Open $title",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(end = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = title,
            variant = LightTextVariant.Heading,
            maxLines = if (wrap) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatPlaybackTime(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.toLong().coerceAtLeast(0) else 0
    return if (total >= 3600) {
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val remainingSeconds = total % 60
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        val minutes = total / 60
        val remainingSeconds = total % 60
        "%d:%02d".format(minutes, remainingSeconds)
    }
}

private fun normalizeProgress(progressText: String): String =
    Regex("(\\d+(?:\\.\\d+)?)\\s*%")
        .find(progressText)
        ?.groupValues
        ?.get(1)
        ?.let { "$it%" }
        .orEmpty()

private fun progressPercent(progressText: String): Int? =
    normalizeProgress(progressText).removeSuffix("%").toDoubleOrNull()?.toInt()?.coerceIn(0, 100)

private fun LoanItem.toAudiobook(): Audiobook {
    val saved = AudiobookProgressStore.read(AudiobookSource.Libby, loanUrl)
    return Audiobook(
        id = loanUrl,
        source = AudiobookSource.Libby,
        title = title,
        author = author,
        playbackReference = loanUrl,
        durationMilliseconds = saved.durationMilliseconds,
        positionMilliseconds = saved.positionMilliseconds,
        playbackSpeed = saved.playbackSpeed,
        completed = saved.completed,
        lastPlayedAtMilliseconds = saved.lastPlayedAtMilliseconds,
        lastUpdatedAtMilliseconds = saved.lastUpdatedAtMilliseconds,
        progressPercentOverride = progressPercent(progressText),
        dueText = normalizeDueText(dueText),
    )
}

private fun PersistedActiveAudiobook.toAudiobook(): Audiobook = Audiobook(
    id = id,
    source = source,
    title = title.ifBlank { progress.title },
    author = author.ifBlank { progress.author },
    playbackReference = playbackReference.ifBlank { progress.playbackReference },
    durationMilliseconds = progress.durationMilliseconds,
    positionMilliseconds = progress.positionMilliseconds,
    playbackSpeed = progress.playbackSpeed,
    completed = progress.completed,
    lastPlayedAtMilliseconds = progress.lastPlayedAtMilliseconds,
    lastUpdatedAtMilliseconds = progress.lastUpdatedAtMilliseconds,
    progressPercentOverride = progress.progressPercentOverride,
    dueText = progress.dueText,
)

private fun PersistedActiveAudiobook.toRememberedPlayerState(): PlayerState = PlayerState(
    title = title.ifBlank { progress.title },
    chapter = author.ifBlank { progress.author }.takeIf { it.isNotBlank() },
    positionSeconds = progress.positionMilliseconds / 1000.0,
    durationSeconds = progress.durationMilliseconds / 1000.0,
    playbackSpeed = progress.playbackSpeed.toDouble(),
    diagnostic = "Connecting to Libby",
    readiness = PlayerReadiness.Preparing,
)

private fun normalizeDueText(dueText: String): String {
    val clean = dueText.replace(Regex("\\s+"), " ").trim()
    if (clean.isBlank()) return ""

    val duration = Regex("(\\d+\\s+days?)", RegexOption.IGNORE_CASE)
        .find(clean)
        ?.value
    if (duration != null) return "Due in $duration"

    return if (clean.startsWith("due", ignoreCase = true)) clean else "Due $clean"
}

private fun trimSpeed(speed: Double): String =
    if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString()
