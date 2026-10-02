package com.tyshi00.livrospeak.library

/**
 * A single library card number on the connected Libby account, scraped live from
 * Libby's own account "Library Cards" page (see LibbyWebPlayer.scrapeLibraryCards)
 * only while the user is actively viewing LibraryCardScreen, after they've passed
 * its PIN gate. Deliberately not persisted anywhere -- a card number is plaintext
 * account data, so it's re-read fresh every time rather than cached to disk or
 * kept in any longer-lived store.
 */
data class LibraryCard(
    val libraryName: String,
    val cardNumber: String,
)
