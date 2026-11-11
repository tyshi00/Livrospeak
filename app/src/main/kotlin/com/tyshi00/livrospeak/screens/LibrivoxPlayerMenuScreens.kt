package com.tyshi00.livrospeak.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.tyshi00.livrospeak.ui.LightBarButton
import com.tyshi00.livrospeak.ui.LightBottomBar
import com.tyshi00.livrospeak.ui.LightIcons
import com.tyshi00.livrospeak.ui.LightLazyScrollView
import com.tyshi00.livrospeak.ui.LightText
import com.tyshi00.livrospeak.ui.LightTextVariant
import com.tyshi00.livrospeak.ui.LightTopBar
import com.tyshi00.livrospeak.ui.LightTopBarCenter
import com.tyshi00.livrospeak.ui.gridUnitsAsDp

/**
 * Menu opened from the list icon on a LibriVox book's player screen (the same
 * bottom-bar slot a Libby book uses for Manage Loan). Same shell and rows as
 * [LibbyToolsScreen].
 */
@Composable
internal fun LibrivoxPlayerMenuScreen(
    onBack: () -> Unit,
    onOpenChapters: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Player",
                ),
                center = LightTopBarCenter.Text("LibriVox"),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                SettingsRow(title = "Chapter Selection", onClick = onOpenChapters)
                SettingsRow(title = "Remove Download", onClick = onRemoveDownload)
            }
        }
    }
}

/**
 * One row per downloaded chapter file. The rows are numbered rather than named:
 * only the audio files are kept on the device, not LibriVox's chapter titles.
 * Opens scrolled to the chapter currently loaded.
 */
@Composable
internal fun LibrivoxChaptersScreen(
    chapterCount: Int,
    currentChapterIndex: Int?,
    onBack: () -> Unit,
    onSelectChapter: (index: Int) -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (currentChapterIndex ?: 0).coerceIn(0, (chapterCount - 1).coerceAtLeast(0)),
    )
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("Chapters"),
            )
            LightLazyScrollView(
                listState = listState,
                uniformItemHeightGridUnits = 5.5f,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp(), top = 0.75f.gridUnitsAsDp()),
            ) {
                items(chapterCount) { index ->
                    SettingsRow(
                        title = if (index == currentChapterIndex) {
                            "Chapter ${index + 1} · Playing"
                        } else {
                            "Chapter ${index + 1}"
                        },
                        onClick = { onSelectChapter(index) },
                    )
                }
            }
        }
    }
}

/**
 * Confirmation before a LibriVox download is deleted from the player's menu.
 * One button, like the delete-tag confirmation: Back is the way to cancel.
 */
@Composable
internal fun LibrivoxRemoveDownloadScreen(
    title: String,
    message: String,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = if (busy) ({}) else onCancel,
                    contentDescription = "Back",
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
                    text = title,
                    variant = LightTextVariant.Subheading,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lighten = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                LightText(
                    text = if (busy) "Removing…" else "Remove this downloaded audiobook?",
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
                    LightBarButton.Text("REMOVE", onClick = if (busy) ({}) else onConfirm),
                ),
            )
        }
    }
}
