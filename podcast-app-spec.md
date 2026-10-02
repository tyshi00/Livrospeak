# Light Podcasts Replacement — Build Instructions for a New Chat

## What this is

A from-scratch, standalone Android app that closely recreates the Light Phone III's
native Podcasts tool, fixes two specific bugs in it, and adds a "mark as listened"
feature that the native tool lacks. This is **not** a feature of Livrospeak — it is
a separate app, kept separate deliberately so Livrospeak stays scoped to audiobooks
per its own AGENTS.md ("Livrospeak is not trying to become a podcast app").

Target device: Light Phone III (Android 13+, small grayscale display, no browser).
Visual language: must feel like a native Light OS tool, not a generic Android app —
same constraint Livrospeak follows.

## Hard requirements carried over from the requesting conversation

- **Keep code comments to an absolute minimum.** This is an explicit, firm style
  requirement — do not add explanatory comments, doc comments, or narration in the
  code beyond what is truly load-bearing (e.g. a comment documenting a genuinely
  non-obvious workaround, in the rare style of the one existing precedent below).
- Must **not** require connecting to Light's own web dashboard. All podcast
  management happens in-app.
- Must reuse Livrospeak's existing Light-styled UI components for visual
  consistency (see "UI components to bring over" below).

## The two bugs being fixed (do not reproduce these)

1. **Autoplay ignores show boundaries.** In the native tool, the Settings screen's
   only option is "Auto-Play / next episode," and it always advances into the next
   episode of the interleaved, all-shows-combined main feed — so finishing a weather
   podcast can autoplay into an unrelated true-crime or storytelling podcast next.
   Fix: make autoplay scope a user setting with two modes:
   - **Autoplay all recents** — current interleaved-across-all-shows behavior
     (the only behavior the native tool has today).
   - **Autoplay same show only** — after an episode finishes, only advance to the
     next unplayed episode of the *same* podcast; if there isn't one, stop.
2. **Inconsistent "return" navigation.** After completing an action — running a
   search, finishing a download — the native tool inconsistently dumps the user
   onto either the Subscribed Podcasts screen or the main combined-episode feed,
   rather than back to whatever screen/context they were actually in. Fix: standard
   back-stack discipline. An action taken from within a screen returns to that same
   screen afterward; only a small number of intentional cases (Subscribed Podcasts
   list, and the main feed for anything triggered from the app's implicit "home")
   should ever be a true reset point, and it must be deliberate, not incidental.
   In Compose Navigation terms: don't `popUpTo` or navigate to a fixed route as a
   side effect of search/download/sync completing — just let the completing screen
   stay in place and update its own state.

## New feature: mark as listened

- Each episode gets a listened/unlistened boolean (persisted per episode, keyed by
  feed + episode GUID, same keying style as Livrospeak's per-book progress keys).
- **Auto-mark on playback completion** (mirror the `setOnCompletionListener` →
  mark-complete pattern already used for progress-saving in Livrospeak's
  `LocalPlaybackController.kt`).
- **Plus a manual toggle available at any time** — from the episode list (e.g. a
  small tap target per row) and from the episode detail/player screen — so a user
  can mark something listened without playing it, or un-mark it after an
  accidental auto-mark.
- Listened state should be visually indicated in every episode list (main feed and
  per-podcast feed) — e.g. dimmed text/reduced-emphasis variant for listened rows,
  consistent with the existing Light monochrome palette (no color-coding — this is
  a grayscale device).

## New feature: in-app podcast management (no dashboard)

One screen (reachable from Settings, matching the native app's information
architecture where Settings is the gateway to configuration) with two ways to add
a podcast:

1. **Manual RSS entry** — a single URL field, same mechanism as Livrospeak's
   existing RSS audiobook feature. Reuse the parsing approach from
   `RssFeedRepository.kt` (standard RSS 2.0 parsing, `<enclosure>` per item,
   `<category>`/`<itunes:category>` handling) as the starting point — podcasts and
   Livrospeak's RSS audiobooks are the same underlying format.
2. **Podcast search** — a text search that queries a podcast directory and returns
   candidate shows (title, author/network, artwork optional — the native tool
   doesn't show artwork and this app shouldn't either, staying consistent with the
   grayscale/text-first aesthetic) with an "Add" action per result. Recommended
   API: the **iTunes Search API**
   (`https://itunes.apple.com/search?term=<query>&media=podcast`) — free, no API
   key or account required, returns a `feedUrl` field directly per result, which is
   exactly the RSS URL needed to subscribe. This keeps the dependency graph small,
   consistent with the project's own stated preference for fewer moving parts. Note
   this is a plain HTTPS GET; no SDK/library dependency needed, an ordinary HTTP
   client returning JSON is enough (or a minimal hand-rolled JSON parse if avoiding
   a JSON library entirely is preferred — no strong opinion here, follow whatever
   Livrospeak already uses for XML/JSON to stay consistent).

Removing a podcast should ask for or otherwise clearly support removing just the
subscription vs. removing subscription + downloaded episodes, mirroring Livrospeak's
existing behavior where removing a downloaded audiobook only deletes the local
offline copy and preserves progress/feed metadata — same idea applies here for
listened-state (don't lose listened history just because a download was deleted).

## UI components to bring over (the "SDK" question, answered)

Yes — hand the new chat actual source files, not just descriptions. These
components are small, self-contained, and have no dependency on Livrospeak's
audiobook-specific domain models, so they can be copied verbatim into the new
project with only the package name changed:

```
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightBarButton.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightBottomBar.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightClickable.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightEmbeddedLp3Keyboard.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightFont.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightFullscreenModal.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightGrid.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightIcon.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightIcons.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightScrollView.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightText.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightTheme.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightThemeController.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightTopBar.kt
app/src/main/kotlin/com/tyshi00/livrospeak/ui/LightUrlInputEditor.kt
```

This is the whole `ui/` package — `gridUnitsAsDp()` lives in `LightGrid.kt`, the
theme/tokens/dark-light switching lives in `LightTheme.kt` +
`LightThemeController.kt`.

Three more small composables are used constantly throughout Livrospeak's screens
but are **not** in the shared `ui/` package — they're private helpers declared
directly inside `PlayerDebugScreen.kt`. They're trivial (a few lines each) so
rather than extracting them from a 3000+ line file, just hand the new chat these
three snippets to recreate as its own top-level (non-private) composables:

```kotlin
@Composable
private fun LivrospeakSurface(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.5f.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = "Toggle $title",
                role = Role.Switch,
                onClick = { onToggle(!checked) },
            )
            .padding(end = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = if (checked) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
            size = LightBarButtonDefaults.ICON_SIZE_UNITS,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.size(width = 1f.gridUnitsAsDp(), height = 1f.gridUnitsAsDp()))
        LightText(text = title, variant = LightTextVariant.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsRow(
    title: String,
    onClick: () -> Unit = {},
    wrap: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (wrap) {
                    Modifier.heightIn(min = 5.5f.gridUnitsAsDp()).padding(vertical = 0.5f.gridUnitsAsDp())
                } else {
                    Modifier.height(5.5f.gridUnitsAsDp())
                },
            )
            .lightClickable(onClickLabel = "Open $title settings", role = Role.Button, onClick = onClick)
            .padding(end = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(text = title, variant = LightTextVariant.Heading, maxLines = if (wrap) 2 else 1, overflow = TextOverflow.Ellipsis)
    }
}
```

(These reproduce the exact spacing/sizing constants Livrospeak uses — 5.5 grid
units per row height, 1 grid unit end padding, etc. — so screens built with them
will match the native app's rhythm without guesswork.)

## Architecture patterns worth reusing (judgment call, not a hard requirement)

- **Playback engine:** Android `MediaPlayer`, same as
  `LocalPlaybackController.kt`. One hard-won lesson from that file worth carrying
  over verbatim: `prepareAsync()` can hang forever with **neither**
  `setOnPreparedListener` nor `setOnErrorListener` ever firing (seen on real
  hardware with a large file). Add a timeout watchdog (~20s) after `prepareAsync()`
  that force-fails to an error state if playback is still stuck in
  Preparing/Buffering when it fires. This is the one case where an explanatory
  comment is worth keeping despite the minimal-comments rule, because the failure
  mode is genuinely non-obvious.
- **Persistence:** small JSON-file-backed stores in app-private storage, one
  responsibility per store (mirroring `AudiobookProgressStore` /
  `LivrospeakSettingsStore` / `GenreStore`) — e.g. a subscriptions store, a
  listened-state store, a settings store (autoplay scope, etc.).
- **Feed parsing:** start from `RssFeedRepository.kt`'s approach rather than
  pulling in a new RSS/Atom library.

## Screens to build (from the 13 reference screenshots)

Reproduce this screen set and flow as closely as possible to the native tool,
with the two bug fixes and the listened-state feature layered in:

1. **Main feed** — combined episodes from every subscribed show, interleaved,
   newest first, each row showing episode title + show name as subtitle + date.
   Bottom bar icons: Settings, Downloads, Refresh, Subscriptions (list icon),
   Bluetooth. Listened episodes shown de-emphasized.
2. **Subscribed Podcasts** — alphabetical list of subscribed shows, each row
   showing show title + author/network subtitle (native tool shows "unknown" when
   a feed doesn't provide author metadata — same fallback here). Tapping a show
   opens its per-podcast episode list. This screen (or a dedicated "+" from it) is
   where the manual-RSS-entry and podcast-search flows live.
3. **Per-podcast episode list** — episodes for one show, title + date per row.
   Bottom icons: sort, refresh, search (searches within that show's episodes).
4. **Episode detail / player** — show name, episode title, date, duration, play
   button, progress bar, a manual "mark as listened" toggle. Bottom icons:
   Settings, Download, playback speed indicator (e.g. "1x"), Bluetooth. Needs both
   a "ready to play" state and a "downloading…" state (matches the two reference
   screenshots of this screen).
5. **Playback speed picker** — simple list: .5x, 1x, 1.25x, 1.5x, 2x.
6. **Downloads** — list of downloaded episodes with two interaction modes: an
   "EDIT" mode showing a per-row download-status icon, and a selection mode
   (labeled "DONE" when active) with a per-row ⊗ icon for removing downloads.
7. **Settings** — Auto-Play (now a choice: All recents / Same show only, per the
   bug fix above), plus whatever else is a natural fit (e.g. default download
   behavior) — keep it as minimal as the native tool's own Settings screen; don't
   add options beyond what's needed for the two fixes plus listened-state.
8. **Sync notice** — small dismissible toast/dialog: "Podcast feeds are syncing in
   the background," with an X to dismiss. Triggered on manual refresh and/or
   periodic background sync.
9. **Text search / keyboard entry** — reuse `LightEmbeddedLp3Keyboard.kt` /
   `LightUrlInputEditor.kt` patterns already built for the Light Phone III's
   input constraints, for both the per-podcast episode search and the
   podcast-directory search box.

## Suggested project setup for the new chat

- New standalone Gradle Android project, own package name (something like
  `com.tyshi00.lightpods` or similar — pick anything that doesn't collide with
  `com.tyshi00.livrospeak`).
- Copy the 15 `ui/` files above in as-is (adjust the package declaration line
  only).
- Copy `LightIcons.kt`'s icon set as a starting point and add any new icons this
  app needs (e.g. a listened/unlistened indicator icon) in the same style.
- Kotlin + Jetpack Compose, same as Livrospeak. JDK 17, Android SDK API 36 target,
  Android 13+ minimum, matching Livrospeak's own requirements since both target
  the same device.
- Minimal comments throughout, per the hard requirement above.

---

This document is the full spec to hand to a new chat to start building. Nothing in
Livrospeak's own codebase needs to change for this work — it's entirely new,
separate code.
