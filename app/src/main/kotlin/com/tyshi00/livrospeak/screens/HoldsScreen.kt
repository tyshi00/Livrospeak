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
import com.tyshi00.livrospeak.library.HoldItem
import com.tyshi00.livrospeak.ui.LightBarButton
import com.tyshi00.livrospeak.ui.LightBottomBar
import com.tyshi00.livrospeak.ui.LightIcons
import com.tyshi00.livrospeak.ui.LightLazyScrollView
import com.tyshi00.livrospeak.ui.LightText
import com.tyshi00.livrospeak.ui.LightTextVariant
import com.tyshi00.livrospeak.ui.LightTopBar
import com.tyshi00.livrospeak.ui.LightTopBarCenter
import com.tyshi00.livrospeak.ui.gridUnitsAsDp
import com.tyshi00.livrospeak.ui.lightClickable

/** Which hold action is currently in flight on [ManageHoldScreen]. */
internal enum class HoldAction { Cancel, Suspend, Borrow }

/**
 * Lists the account's current Libby holds. Reached from [LibbyToolsScreen]. Follows
 * the same shell/list pattern as the Books screen's own list (LightLazyScrollView of
 * uniform-height rows), but doesn't reuse BookRow/Audiobook -- a hold isn't a
 * playable audiobook yet, so it gets its own small row here instead of stretching
 * that model to cover a state it was never meant to represent.
 */
@Composable
internal fun HoldsScreen(
    holds: List<HoldItem>,
    loading: Boolean,
    message: String,
    onBack: () -> Unit,
    onOpenHold: (HoldItem) -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("Holds"),
            )
            when {
                loading && holds.isEmpty() -> HoldsStatus("Loading holds…", Modifier.weight(1f))
                message.isNotBlank() && holds.isEmpty() -> HoldsStatus(message, Modifier.weight(1f))
                holds.isEmpty() -> HoldsStatus("No holds", Modifier.weight(1f))
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
                        items(holds, key = { "${it.cardId}:${it.titleId}" }) { hold ->
                            HoldRow(hold = hold, onClick = { onOpenHold(hold) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldsStatus(message: String, modifier: Modifier = Modifier) {
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
private fun HoldRow(hold: HoldItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.5f.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = "Manage hold on ${hold.title}",
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
            text = hold.title,
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = hold.author.ifBlank { "Unknown Author" },
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = hold.status.ifBlank { if (hold.isReady) "Ready for borrowing" else "Waiting" },
            variant = LightTextVariant.Superfine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lighten = true,
        )
    }
}

/**
 * Manage a single hold -- mirrors ManageLoanScreen's shell exactly (title + status
 * heading + bottom bar). No dismiss button here: the Back arrow already on
 * LightTopBar covers that. The bottom bar always offers Cancel and Suspend (same
 * reasoning used to drop ManageLoanScreen's redundant CANCEL text button), and adds
 * a third Borrow action once the hold is actually ready for checkout -- a waiting
 * hold has nothing to borrow yet, so that button only appears when [HoldItem.isReady]
 * is true.
 */
@Composable
internal fun ManageHoldScreen(
    hold: HoldItem,
    message: String,
    busyAction: HoldAction?,
    onBack: () -> Unit,
    onCancelHold: () -> Unit,
    onSuspendHold: () -> Unit,
    onBorrowHold: () -> Unit,
) {
    val busy = busyAction != null
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Holds",
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
                        text = hold.title,
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
                        HoldAction.Cancel -> "Cancelling this hold…"
                        HoldAction.Suspend -> "Suspending this hold…"
                        HoldAction.Borrow -> "Borrowing this title…"
                        null -> "Manage this hold"
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
                // Three equal-width text buttons didn't fit a real device's screen --
                // BORROW/SUSPEND (wide glyphs: double O + W, wide U/S) truncated to
                // "BORR…"/"SUSPE…" while only the narrower CANCEL rendered in full.
                // Switched to the same icon-text-icon arrangement ManageLoanScreen's
                // own 3-item bar already uses (Renew icon / TAG text / Return icon) --
                // LightBottomBar spaces that evenly on its own (isMixedIconTextIconLayout)
                // instead of forcing 3 fixed-width slots, which is exactly the class of
                // bug this sidesteps rather than fights. BORROW keeps the clear text
                // treatment since it's the one new, primary action this screen exists
                // for; PAUSE/CLOSE stand in for the two already-established, well
                // understood actions (no dedicated suspend/cancel icon exists in this
                // app's trimmed icon set, but pause-for-suspend and close-for-cancel
                // are both common enough pairings to read clearly without a label).
                items = if (hold.isReady) {
                    listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.PAUSE,
                            onClick = onSuspendHold.takeIf { !busy } ?: {},
                            contentDescription = "Suspend Hold",
                            // PAUSE's glyph reads visually larger than CLOSE's at the
                            // shared default size (2f) -- sized down to match CLOSE's
                            // apparent size rather than its literal icon-box size.
                            sizeUnits = 1.5f,
                        ),
                        LightBarButton.Text("BORROW", onClick = onBorrowHold.takeIf { !busy } ?: {}),
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            onClick = onCancelHold.takeIf { !busy } ?: {},
                            contentDescription = "Cancel Hold",
                        ),
                    )
                } else {
                    listOf(
                        LightBarButton.Text("CANCEL", onClick = onCancelHold.takeIf { !busy } ?: {}),
                        LightBarButton.Text("SUSPEND", onClick = onSuspendHold.takeIf { !busy } ?: {}),
                    )
                },
            )
        }
    }
}
