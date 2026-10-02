package com.tyshi00.livrospeak.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tyshi00.livrospeak.ui.LightBarButton
import com.tyshi00.livrospeak.ui.LightIcons
import com.tyshi00.livrospeak.ui.LightScrollView
import com.tyshi00.livrospeak.ui.LightTopBar
import com.tyshi00.livrospeak.ui.LightTopBarCenter
import com.tyshi00.livrospeak.ui.gridUnitsAsDp

/**
 * Hub screen reached from the Books screen's menu icon (the slot that used to be a
 * direct "Now Playing" shortcut -- dropped since Books already sorts recent-first,
 * so the current book is already at the top of that list). Lists the Libby-account
 * tools that don't fit as a bottom-bar icon of their own. Follows the exact
 * shell/row pattern SettingsScreen already uses (LivrospeakSurface + LightTopBar +
 * LightScrollView of SettingsRow items) rather than introducing a new one.
 */
@Composable
internal fun LibbyToolsScreen(
    onBack: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenHolds: () -> Unit,
    onOpenLibraryCards: () -> Unit,
) {
    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("Libby Tools"),
            )

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                SettingsRow(title = "Saved Tags - Libby", onClick = onOpenTags)
                SettingsRow(title = "Holds", onClick = onOpenHolds)
                SettingsRow(title = "Library Cards", onClick = onOpenLibraryCards)
            }
        }
    }
}
