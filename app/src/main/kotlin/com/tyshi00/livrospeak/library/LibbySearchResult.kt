package com.tyshi00.livrospeak.library

enum class LibbyAvailability {
    Available,
    Wait,
    /** Already on the user's shelf (as a loan or an existing hold). */
    Owned,
    Unknown,
}

/**
 * One row from a Libby catalog search. [titleId] is Libby's internal numeric title id,
 * scraped from the result tile's own class list (same `data-title_(\d+)` convention the
 * shelf scraper already relies on in [LibbyWebPlayer.scrapeAudiobookLoans]) -- it's the
 * only stable handle available for re-selecting this exact tile later to borrow/hold it.
 */
data class LibbySearchResult(
    val titleId: String,
    val title: String,
    val author: String,
    val coverUrl: String = "",
    val availability: LibbyAvailability = LibbyAvailability.Unknown,
    /** Raw button/link text Livrospeak saw on the tile -- shown to the user as a hint, and
     *  useful for you to see, on-device, what Libby is actually rendering for a given
     *  result while tuning the scraping/matching logic below. */
    val availabilityText: String = "",
    /** Which Libby search-results page this was scraped from (search now merges
     *  several pages -- see MAX_LIBBY_SEARCH_PAGES). Needed to re-navigate the
     *  WebView back to the right page before borrowing/holding it, since by the
     *  time the user taps a result the WebView may already have moved on to a
     *  later page. */
    val page: Int = 1,
    /** Which linked library card (see [LibbyWebPlayer.LIBBY_LIBRARIES]) this result
     *  came from -- search now queries every configured library and merges the
     *  results, so re-navigating to this exact result later needs both the right
     *  library AND the right page, not just the page. The SAME titleId can
     *  legitimately show up under two different libraries (OverDrive's catalog is
     *  shared; only availability is per-library), so [libraryId] is also part of
     *  what makes a result unique, not just [titleId]. */
    val libraryId: String = "",
    /** Display name of [libraryId], shown to the user so they know which of their
     *  library cards a result is available through. */
    val libraryName: String = "",
)
