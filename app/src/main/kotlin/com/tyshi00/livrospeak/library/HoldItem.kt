package com.tyshi00.livrospeak.library

/**
 * A single Libby hold, scraped from the Holds tab of the hidden shelf WebView.
 * Deliberately separate from [LoanItem]/[Audiobook] (not merged into the
 * source-independent domain model) since a hold isn't a playable audiobook yet --
 * it only ever feeds the Holds screen, which is Libby-specific.
 *
 * [cardId]/[titleId] are kept as plain fields here (unlike [LoanItem], which
 * recovers them from its loanUrl) because a hold has no equivalent "open" URL to
 * derive them from -- the cancel/suspend links are the only place Libby exposes
 * them, so [LibbyWebPlayer.scrapeHolds] parses them out once at scrape time.
 */
data class HoldItem(
    val title: String,
    val author: String,
    val cardId: String,
    val titleId: String,
    val coverUrl: String = "",
    val status: String = "",
    val isReady: Boolean = false,
)
