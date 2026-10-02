package com.tyshi00.livrospeak.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.tyshi00.livrospeak.library.LibbyTag
import com.tyshi00.livrospeak.library.TaggedTitle
import com.tyshi00.livrospeak.library.displayName
import com.tyshi00.livrospeak.ui.LightBarButton
import com.tyshi00.livrospeak.ui.LightBottomBar
import com.tyshi00.livrospeak.ui.LightIcons
import com.tyshi00.livrospeak.ui.LightLazyScrollView
import com.tyshi00.livrospeak.ui.LightScrollView
import com.tyshi00.livrospeak.ui.LightText
import com.tyshi00.livrospeak.ui.LightTextVariant
import com.tyshi00.livrospeak.ui.LightTopBar
import com.tyshi00.livrospeak.ui.LightTopBarCenter
import com.tyshi00.livrospeak.ui.LightUrlInputEditor
import com.tyshi00.livrospeak.ui.gridUnitsAsDp
import com.tyshi00.livrospeak.ui.lightClickable

/**
 * Lists the account's own Libby tags ("Saved", "To Be Read", or whatever the user has
 * named them). Reached from [LibbyToolsScreen]. Follows the same shell/list pattern
 * as [HoldsScreen] -- a tag isn't a playable audiobook, so it gets its own small row
 * rather than stretching the Audiobook/BookRow model to cover it.
 */
@Composable
internal fun TagsScreen(
    tags: List<LibbyTag>,
    loading: Boolean,
    message: String,
    onBack: () -> Unit,
    onOpenTag: (LibbyTag) -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("Saved Tags"),
            )
            when {
                loading && tags.isEmpty() -> TagsStatus("Loading tags…", Modifier.weight(1f))
                message.isNotBlank() && tags.isEmpty() -> TagsStatus(message, Modifier.weight(1f))
                tags.isEmpty() -> TagsStatus("No tags yet", Modifier.weight(1f))
                else -> {
                    LightLazyScrollView(
                        uniformItemHeightGridUnits = 5f,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(
                                start = 1.5f.gridUnitsAsDp(),
                                top = 0.75f.gridUnitsAsDp(),
                            ),
                    ) {
                        items(tags, key = { it.id }) { tag ->
                            TagRow(tag = tag, onClick = { onOpenTag(tag) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagsStatus(message: String, modifier: Modifier = Modifier) {
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
private fun TagRow(tag: LibbyTag, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(5f.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = "Open ${tag.displayName}",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                end = 1f.gridUnitsAsDp(),
                top = 0.25f.gridUnitsAsDp(),
                bottom = 0.25f.gridUnitsAsDp(),
            ),
        verticalArrangement = Arrangement.Center,
    ) {
        // Shows displayName, not the raw name -- a tag whose own name is just an
        // emoji (several real ones are) falls back to a readable label instead
        // (see LibbyTag.displayName's doc comment). The real name is still what
        // LibbyWebPlayer matches against Libby's own dialog everywhere else.
        LightText(
            text = tag.displayName,
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (tag.titleCount > 0) {
            LightText(
                text = if (tag.titleCount == 1) "1 title" else "${tag.titleCount} titles",
                variant = LightTextVariant.Superfine,
                lighten = true,
            )
        }
    }
}

/**
 * Name entry for a brand new tag, created and applied to one specific title in the
 * same step -- confirmed via real capture that this is the only way Libby lets you
 * create a tag at all, so this is reached from [ManageTitleTagsScreen]'s "+ New Tag"
 * button rather than from the plain Saved Tags list. Reuses [LightUrlInputEditor] as
 * a generic single-line text entry screen (its "Url" naming is leftover from its
 * original RSS use; the keyboard flow itself is plain text input). Libby's own
 * creation dialog also has an optional description field, but that's left out here
 * to keep this to one simple step -- consistent with Livrospeak's "fewer taps, less
 * UI" design philosophy -- and [LibbyWebPlayer.createTagForTitle] still accepts one
 * if that's ever revisited.
 *
 * [keyboardKey] is passed straight through to [LightUrlInputEditor], whose own doc
 * comment explains why: it's a cache key into the host Activity's ViewModelStore, so
 * a fixed/default value here would mean every "+ New Tag" session for the rest of
 * the app's process lifetime reuses the exact same keyboard ViewModel -- carrying
 * over its internal letters/symbols layout mode from whatever an unrelated, earlier
 * New Tag session last left it in. The caller is expected to pass a freshly
 * generated value each time this flow is newly entered (see
 * `manageTagsNewKeyboardSession` in PlayerDebugScreen.kt), not a constant.
 */
@Composable
internal fun NewTagScreen(
    name: String,
    onNameChange: (String) -> Unit,
    message: String,
    submitting: Boolean,
    keyboardKey: String = "LivrospeakNewTagKeyboard",
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    LivrospeakSurface {
        LightUrlInputEditor(
            value = name,
            onValueChange = onNameChange,
            message = message,
            submitting = submitting,
            onBack = onBack,
            onDone = onCreate,
            title = "New Tag",
            doneLabel = "CREATE",
            submittingLabel = "CREATING…",
            keyboardKey = keyboardKey,
            backContentDescription = "Back to Tag",
        )
    }
}

/**
 * Lists the titles under a single tag. Tapping a title currently just shows whether
 * it's already on your shelf -- jumping straight into playback or a borrow/hold flow
 * from here is deferred for now to keep this feature's first pass narrowly scoped,
 * the same way Holds shipped read-plus-two-actions before anything more elaborate.
 *
 * [ownedTitleIds] is the set of titleIds pulled from the real loans list -- the
 * tag-detail page itself carries no ownership info to scrape (see [TaggedTitle]'s
 * doc comment), so "on your shelf" vs. "not currently borrowed" is decided here by
 * cross-referencing against what Livrospeak already knows you've borrowed, rather
 * than anything read off this page.
 *
 * [onRename]/[onDelete] mirror Libby's own real "ACTIONS" button on this same page
 * (confirmed via real capture: it opens a popover with Rename Tag / Export Tag /
 * Delete Tag) -- flattened into two direct bottom-bar buttons here instead of
 * reproducing that extra popover step, the same way ManageLoanScreen/
 * ManageHoldScreen already flatten Libby's own multi-step flows into direct
 * actions. Export isn't offered -- nothing in Livrospeak needs it.
 */
@Composable
internal fun TagDetailScreen(
    tag: LibbyTag,
    titles: List<TaggedTitle>,
    ownedTitleIds: Set<String>,
    loading: Boolean,
    message: String,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Saved Tags",
                ),
                center = LightTopBarCenter.Text(tag.displayName),
            )
            when {
                loading && titles.isEmpty() -> TagsStatus("Loading titles…", Modifier.weight(1f))
                message.isNotBlank() && titles.isEmpty() -> TagsStatus(message, Modifier.weight(1f))
                titles.isEmpty() -> TagsStatus("No titles in this tag yet", Modifier.weight(1f))
                else -> {
                    LightLazyScrollView(
                        uniformItemHeightGridUnits = 5f,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(
                                start = 1.5f.gridUnitsAsDp(),
                                top = 0.75f.gridUnitsAsDp(),
                            ),
                    ) {
                        items(titles, key = { it.titleId }) { taggedTitle ->
                            TaggedTitleRow(
                                taggedTitle = taggedTitle,
                                isOwned = ownedTitleIds.contains(taggedTitle.titleId),
                            )
                        }
                    }
                }
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text("RENAME", onClick = onRename),
                    LightBarButton.Text("DELETE", onClick = onDelete),
                ),
            )
        }
    }
}

/**
 * Renames the tag being viewed in [TagDetailScreen]. Reuses [LightUrlInputEditor]
 * pre-filled with the tag's current real name (not its [LibbyTag.displayName],
 * which can substitute a readable label for an emoji-only name -- editing should
 * start from the actual string Libby matches against). [keyboardKey] needs a fresh
 * value each time this screen is freshly entered, same as [NewTagScreen]'s own
 * doc comment explains -- otherwise a stale keyboard layout (symbols/numeric mode
 * left over from a previous rename or tag-creation session) would carry over here
 * too, the exact bug already fixed for New Tag.
 */
@Composable
internal fun RenameTagScreen(
    name: String,
    onNameChange: (String) -> Unit,
    message: String,
    submitting: Boolean,
    keyboardKey: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    LivrospeakSurface {
        LightUrlInputEditor(
            value = name,
            onValueChange = onNameChange,
            message = message,
            submitting = submitting,
            onBack = onBack,
            onDone = onDone,
            title = "Rename Tag",
            doneLabel = "DONE",
            submittingLabel = "RENAMING…",
            keyboardKey = keyboardKey,
            backContentDescription = "Back to Tag",
        )
    }
}

/**
 * Confirms deleting the tag being viewed in [TagDetailScreen]. This confirmation
 * is NOT redundant with Libby's own real "Are you sure you want to delete this
 * tag?" popover -- that one happens inside the hidden WebView, invisible to the
 * user, so this screen is the only confirmation they actually see before
 * [LibbyWebPlayer.deleteTag] drives through Libby's real one on their behalf.
 * Mirrors DisconnectLibbyScreen/ResetLibraryCardPinScreen's shell exactly, for the
 * same reason those exist.
 */
@Composable
internal fun ConfirmDeleteTagScreen(
    tagName: String,
    message: String,
    submitting: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Tag",
                ),
                center = LightTopBarCenter.Text("Delete Tag"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Delete \"$tagName\"? This can't be undone.",
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(end = 1.5f.gridUnitsAsDp()),
                )
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(end = 1.5f.gridUnitsAsDp()),
                    )
                }
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(
                        text = if (submitting) "DELETING…" else "YES, DELETE",
                        onClick = if (submitting) ({}) else onDelete,
                    ),
                ),
            )
        }
    }
}

/**
 * Lets the user add/remove [titleName] from any of their existing tags, from either
 * ManageLoanScreen (an owned title) or the Libby search confirm screen (a
 * not-yet-borrowed one) -- see PlayerDebugScreen's manageTagsForTitle wiring for how
 * either caller reaches this same screen. Reuses [ToggleRow] (the same checkbox-style
 * row AudiobookSourceSettingsScreen uses) rather than inventing a new row style for
 * what is, functionally, the same kind of on/off control.
 *
 * Also offers "+ New Tag" in its bottom bar -- confirmed via real capture that
 * creating a tag is only possible while tagging a specific title (there is no
 * standalone creation UI), so this screen, not the plain Saved Tags list, is the
 * one real entry point for it. Always shown, even with zero existing tags, since
 * that's exactly the case where creating the first one is most needed.
 */
@Composable
internal fun ManageTitleTagsScreen(
    titleName: String,
    tags: List<LibbyTag>,
    membership: Set<String>,
    loading: Boolean,
    busyTagId: String?,
    message: String,
    onBack: () -> Unit,
    onToggleTag: (LibbyTag) -> Unit,
    onNewTag: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("Tag"),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = titleName,
                    variant = LightTextVariant.Subheading,
                    align = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                loading && tags.isEmpty() -> TagsStatus("Loading tags…", Modifier.weight(1f))
                tags.isEmpty() -> TagsStatus("No tags yet", Modifier.weight(1f))
                else -> {
                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1.5f.gridUnitsAsDp()),
                    ) {
                        tags.forEach { tag ->
                            // Same displayName treatment as the plain Saved Tags list
                            // -- membership/toggle matching below still uses tag.name
                            // verbatim, since that's the real string Libby's own
                            // dialog has to match against.
                            ToggleRow(
                                title = tag.displayName,
                                checked = membership.contains(tag.name),
                                onToggle = { if (busyTagId == null) onToggleTag(tag) },
                                busy = busyTagId == tag.id,
                            )
                        }
                    }
                }
            }
            if (message.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        lighten = true,
                    )
                }
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text("NEW TAG", onClick = onNewTag),
                ),
            )
        }
    }
}

@Composable
private fun TaggedTitleRow(taggedTitle: TaggedTitle, isOwned: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(5f.gridUnitsAsDp())
            .padding(
                end = 1f.gridUnitsAsDp(),
                top = 0.25f.gridUnitsAsDp(),
                bottom = 0.25f.gridUnitsAsDp(),
            ),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = taggedTitle.title,
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = taggedTitle.author.ifBlank { "Unknown Author" },
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = if (isOwned) "On your shelf" else "Not currently borrowed",
            variant = LightTextVariant.Superfine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lighten = true,
        )
    }
}
