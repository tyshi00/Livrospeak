package com.tyshi00.livrospeak.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.tyshi00.livrospeak.library.LibrivoxCatalogRepository
import com.tyshi00.livrospeak.library.LibrivoxDownloadManager
import com.tyshi00.livrospeak.library.LibrivoxDownloadState
import com.tyshi00.livrospeak.library.LibrivoxDownloadStatus
import com.tyshi00.livrospeak.library.LibrivoxSearchOutcome
import com.tyshi00.livrospeak.library.LibrivoxSearchResult
import com.tyshi00.livrospeak.ui.LightBarButton
import com.tyshi00.livrospeak.ui.LightLazyScrollView
import com.tyshi00.livrospeak.ui.LightText
import com.tyshi00.livrospeak.ui.LightTextVariant
import com.tyshi00.livrospeak.ui.LightTopBar
import com.tyshi00.livrospeak.ui.LightTopBarCenter
import com.tyshi00.livrospeak.ui.LightUrlInputEditor
import com.tyshi00.livrospeak.ui.gridUnitsAsDp
import com.tyshi00.livrospeak.ui.lightClickable
import kotlinx.coroutines.launch

/**
 * Search LibriVox's public catalog, download a book, and play it.
 *
 * Deliberately self-contained: search text, search results, and download
 * state (via [LibrivoxDownloadManager], read directly) all live here rather
 * than being threaded through the much larger player screen. Only [onBack]
 * and [onPlay] cross that boundary, since only the caller can switch the app
 * over to the Player screen.
 */
@Composable
internal fun LibrivoxScreen(
    onBack: () -> Unit,
    onPlay: (bookId: String, title: String, author: String) -> Unit,
    // false (the default) is this screen's entry from Settings: land on the
    // downloaded-books view. true is the entry from the Books search chooser:
    // land straight on the search input, and Back from there leaves the screen.
    startOnSearchInput: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var showSearchInput by remember { mutableStateOf(startOnSearchInput) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<LibrivoxSearchResult>>(emptyList()) }
    var message by remember { mutableStateOf("Search LibriVox for a public-domain audiobook.") }
    var hasSearched by remember { mutableStateOf(false) }
    val downloads by LibrivoxDownloadManager.states.collectAsState()

    fun runSearch() {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        searching = true
        hasSearched = true
        scope.launch {
            when (val outcome = LibrivoxCatalogRepository.search(trimmed)) {
                is LibrivoxSearchOutcome.Success -> {
                    results = outcome.results
                    message = ""
                }
                LibrivoxSearchOutcome.Empty -> {
                    results = emptyList()
                    message = "No LibriVox results for “$trimmed”."
                }
                is LibrivoxSearchOutcome.NetworkError -> {
                    results = emptyList()
                    // Shows the actual reason (an HTTP status LibriVox sent back, or
                    // the real exception if the request never completed at all).
                    message = "Couldn't reach LibriVox (${outcome.detail})."
                }
            }
            searching = false
        }
    }

    // Entered from the search chooser: there is no downloaded-books view in that
    // flow, so Back from the search input leaves the screen (back to the chooser).
    // Entered from Settings: Back returns to the downloaded-books view.
    val onInputBack: () -> Unit = if (startOnSearchInput) {
        onBack
    } else {
        { showSearchInput = false }
    }

    // Back from a search's results (or its "no results"/error message) returns to
    // the search input with the query still there, the same way Libby's results
    // page does. The search state is cleared so that the input's own Back (above)
    // doesn't land on these same results again.
    val onResultsBack: () -> Unit = {
        hasSearched = false
        results = emptyList()
        message = "Search LibriVox for a public-domain audiobook."
        showSearchInput = true
    }

    if (showSearchInput) {
        // Wrapped in LivrospeakSurface like every other LightUrlInputEditor caller:
        // the editor has no opaque background of its own, and the hidden Libby
        // WebView is mounted underneath the native UI at all times, so without
        // this the Libby web page shows through.
        LivrospeakSurface {
            LightUrlInputEditor(
                value = query,
                onValueChange = { query = it },
                message = "",
                submitting = searching,
                onBack = onInputBack,
                onDone = {
                    if (query.isNotBlank()) {
                        showSearchInput = false
                        runSearch()
                    }
                },
                title = "Search LibriVox",
                doneLabel = "SEARCH",
                submittingLabel = "SEARCHING…",
                keyboardKey = "LivrospeakLibrivoxSearchKeyboard",
                backContentDescription = "Back",
                underline = false,
            )
        }
        return
    }

    val downloadedOnly = remember(downloads) {
        downloads.values
            .filter { it.status == LibrivoxDownloadStatus.Downloaded }
            .sortedBy { it.title.lowercase() }
    }

    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = com.tyshi00.livrospeak.ui.LightIcons.BACK,
                    onClick = if (hasSearched) onResultsBack else onBack,
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("LibriVox"),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp())
                    .lightClickable(
                        onClickLabel = "Search LibriVox",
                        role = Role.Button,
                        onClick = { showSearchInput = true },
                    ),
            ) {
                LightText(
                    text = query.ifBlank { "Search LibriVox…" },
                    variant = LightTextVariant.Subheading,
                    underline = true,
                    lighten = query.isBlank(),
                )
            }
            when {
                hasSearched -> {
                    if (searching && results.isEmpty()) {
                        LibrivoxStatus("Searching…", Modifier.weight(1f))
                    } else if (results.isEmpty()) {
                        LibrivoxStatus(message, Modifier.weight(1f))
                    } else {
                        LightLazyScrollView(
                            uniformItemHeightGridUnits = 6.5f,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(
                                    start = 1.5f.gridUnitsAsDp(),
                                    top = 0.25f.gridUnitsAsDp(),
                                ),
                        ) {
                            items(results, key = { it.id }) { result ->
                                LibrivoxResultRow(
                                    result = result,
                                    download = downloads[result.id],
                                    onDownload = { LibrivoxDownloadManager.start(result) },
                                    onPlay = { onPlay(result.id, result.title, result.author) },
                                    onRemove = { scope.launch { LibrivoxDownloadManager.removeDownload(result.id) } },
                                )
                            }
                        }
                    }
                }
                downloadedOnly.isEmpty() -> LibrivoxStatus(message, Modifier.weight(1f))
                else -> {
                    LightLazyScrollView(
                        uniformItemHeightGridUnits = 6.5f,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(
                                start = 1.5f.gridUnitsAsDp(),
                                top = 0.25f.gridUnitsAsDp(),
                            ),
                    ) {
                        items(downloadedOnly, key = { it.bookId }) { state ->
                            LibrivoxDownloadRow(
                                state = state,
                                onPlay = { onPlay(state.bookId, state.title, state.author) },
                                onRemove = { scope.launch { LibrivoxDownloadManager.removeDownload(state.bookId) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrivoxStatus(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = message, variant = LightTextVariant.Paragraph, align = TextAlign.Center)
    }
}

@Composable
private fun LibrivoxResultRow(
    result: LibrivoxSearchResult,
    download: LibrivoxDownloadState?,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val status = download?.status ?: LibrivoxDownloadStatus.NotDownloaded
    val primaryLabel = when (status) {
        LibrivoxDownloadStatus.NotDownloaded -> "Download"
        LibrivoxDownloadStatus.Downloading -> downloadProgressLabel(download)
        LibrivoxDownloadStatus.Extracting -> "Extracting…"
        LibrivoxDownloadStatus.Downloaded -> "Play"
        LibrivoxDownloadStatus.Failed -> "Retry"
    }
    val clickable = status == LibrivoxDownloadStatus.NotDownloaded ||
        status == LibrivoxDownloadStatus.Downloaded ||
        status == LibrivoxDownloadStatus.Failed
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.5f.gridUnitsAsDp())
            .let {
                if (clickable) {
                    it.lightClickable(
                        onClickLabel = "$primaryLabel ${result.title}",
                        role = Role.Button,
                        onClick = if (status == LibrivoxDownloadStatus.Downloaded) onPlay else onDownload,
                    )
                } else {
                    it
                }
            }
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
        LightText(
            text = listOf(result.totalTimeText, primaryLabel).filter { it.isNotBlank() }.joinToString(" · "),
            variant = LightTextVariant.Superfine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lighten = true,
        )
    }
}

@Composable
private fun LibrivoxDownloadRow(
    state: LibrivoxDownloadState,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.5f.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = "Play ${state.title}",
                role = Role.Button,
                onClick = onPlay,
            )
            .padding(
                end = 1f.gridUnitsAsDp(),
                top = 0.25f.gridUnitsAsDp(),
                bottom = 0.25f.gridUnitsAsDp(),
            ),
        verticalArrangement = Arrangement.Top,
    ) {
        LightText(
            text = state.title,
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = state.author.ifBlank { "Unknown Author" },
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .lightClickable(
                    onClickLabel = "Remove downloaded ${state.title}",
                    role = Role.Button,
                    onClick = onRemove,
                ),
        ) {
            LightText(
                text = "Downloaded · Remove",
                variant = LightTextVariant.Superfine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lighten = true,
            )
        }
    }
}

private fun downloadProgressLabel(state: LibrivoxDownloadState?): String {
    val total = state?.totalBytes ?: 0
    val downloaded = state?.bytesDownloaded ?: 0
    if (total <= 0) return "Downloading…"
    val percent = ((downloaded * 100) / total).coerceIn(0, 100)
    return "Downloading $percent%"
}
