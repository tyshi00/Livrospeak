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
internal enum class HoldAction { Cancel, Suspend }

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
 * LightTopBar covers that, so the bottom bar only needs the two real actions,
 * Cancel and Suspend (same reasoning used to drop ManageLoanScreen's redundant
 * CANCEL text button).
 */
@Composable
internal fun ManageHoldScreen(
    hold: HoldItem,
    message: String,
    busyAction: HoldAction?,
    onBack: () -> Unit,
    onCancelHold: () -> Unit,
    onSuspendHold: () -> Unit,
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
                items = listOf(
                    LightBarButton.Text("CANCEL", onClick = onCancelHold.takeIf { !busy } ?: {}),
                    LightBarButton.Text("SUSPEND", onClick = onSuspendHold.takeIf { !busy } ?: {}),
                ),
            )
        }
    }
}
