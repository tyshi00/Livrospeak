package com.tyshi00.livrospeak.library

/**
 * A single Libby tag (e.g. "Saved" or "To Be Read"), scraped from
 * `https://libbyapp.com/tags` -- a real, directly loadable Libby route (confirmed
 * via real device capture; an earlier guess at "/shelf/tags" was confirmed wrong).
 * [id] is the raw URL segment after `/tags/tag/` from the row's own link (the same
 * value [LibbyWebPlayer].loadTagPage needs to reopen it directly), recovered the
 * same way [HoldItem] recovers cardId/titleId from its cancel/suspend links -- see
 * [LibbyWebPlayer].scrapeTags.
 */
data class LibbyTag(
    val id: String,
    val name: String,
    val titleCount: Int = 0,
    // Both scraped from the same `.tag-plank` component [name] comes from (see
    // LibbyWebPlayer.scrapeTags/scrapeSaveDialogTags) -- [behavior] is "Regular
    // Tag" for a user-created tag or "Smart Tag: Borrowed"/"Smart Tag: Notify
    // Me"/"Smart Tag: Sampled" for Libby's built-in ones, and [description] is
    // either a user-entered description or Libby's own auto-generated one (e.g.
    // "Titles I've saved since 25 Mar 2026."). Exist only to back [displayName]
    // below -- nothing else should read them directly.
    val behavior: String = "",
    val description: String = "",
)

/** A real tag's own text is allowed to be nothing but an emoji (several of the
 *  user's real tags are exactly this) -- that's fine for Libby's own app, which
 *  always shows it next to a colorful tile, but reads as an unlabeled glyph in
 *  Livrospeak's plain-text UI. For a tag whose [LibbyTag.name] has no actual
 *  letters or digits in it, this falls back to something readable instead: a
 *  Smart Tag's own behavior label with the "Smart Tag: " prefix stripped (e.g.
 *  "Borrowed", "Notify Me", "Sampled"), or failing that, the tag's description.
 *  [LibbyTag.name] itself is left untouched by this -- callers still need the
 *  real name verbatim to match it against Libby's own dialog when toggling or
 *  creating (see LibbyWebPlayer.toggleTagForTitle/createTagForTitle). */
private val READABLE_TEXT = Regex("[\\p{L}\\p{N}]")

// Libby's own auto-generated description for a tag that's never had a custom one
// set reads like "Titles I've saved since 25 Mar 2026." -- fine as a description,
// but as a fallback DISPLAY NAME it's long enough to truncate in the single-line
// rows this backs (confirmed from a real screenshot: "Titles I've saved since 25
// Mar ..."). Matches the verb out of that sentence so it can be shown instead as
// the much shorter "Titles Saved" (or "Titles Sampled", etc.) -- covers whichever
// auto-generated verb Libby uses, not just "saved", without hardcoding each one.
private val AUTO_GENERATED_DESCRIPTION = Regex("^Titles I[’']ve (\\w+) since", RegexOption.IGNORE_CASE)

val LibbyTag.displayName: String
    get() {
        if (READABLE_TEXT.containsMatchIn(name)) return name
        val behaviorLabel = behavior.removePrefix("Smart Tag:").trim()
        if (behaviorLabel.isNotBlank() && !behaviorLabel.equals("Regular Tag", ignoreCase = true)) {
            return behaviorLabel
        }
        AUTO_GENERATED_DESCRIPTION.find(description)?.let { match ->
            val verb = match.groupValues[1].replaceFirstChar { it.uppercase() }
            return "Titles $verb"
        }
        return description.ifBlank { name }
    }

/**
 * A single title under a given tag, scraped from that tag's own detail page
 * (confirmed via real capture to use `.title-plank-*` markup, not `.title-tile` --
 * see [LibbyWebPlayer].scrapeTitlesForTag). Deliberately separate from
 * [LoanItem]/[HoldItem]/Audiobook (not merged into the source-independent domain
 * model) -- a tagged title isn't necessarily borrowed or held, it might just be
 * saved for later. The tag-detail page itself carries no ownership/availability
 * info (unlike `.title-tile`, there's no "Open Audiobook"/"Borrow" text to read),
 * so whether a tagged title is currently on the shelf is deliberately NOT a field
 * here -- callers cross-reference [titleId] against the real loans list instead.
 */
data class TaggedTitle(
    val titleId: String,
    val title: String,
    val author: String,
    val coverUrl: String = "",
)
