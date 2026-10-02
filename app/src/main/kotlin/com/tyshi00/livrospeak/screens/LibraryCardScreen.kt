package com.tyshi00.livrospeak.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tyshi00.livrospeak.LibbyWebPlayer
import com.tyshi00.livrospeak.library.LibraryCard
import com.tyshi00.livrospeak.library.LibraryCardPinStore
import com.tyshi00.livrospeak.ui.LightBarButton
import com.tyshi00.livrospeak.ui.LightIcons
import com.tyshi00.livrospeak.ui.LightScrollView
import com.tyshi00.livrospeak.ui.LightText
import com.tyshi00.livrospeak.ui.LightTextVariant
import com.tyshi00.livrospeak.ui.LightThemeTokens
import com.tyshi00.livrospeak.ui.LightTopBar
import com.tyshi00.livrospeak.ui.LightTopBarCenter
import com.tyshi00.livrospeak.ui.gridUnitsAsDp
import com.tyshi00.livrospeak.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gated behind a 4-digit PIN (see [LibraryCardPinStore]) before showing the
 * account's library card numbers -- mirroring the one bit of protection the real
 * Libby app itself doesn't offer here, since it shows a card's number as plain
 * text with nothing in front of it. Scraping only ever happens on demand, right
 * after the PIN is entered correctly: there's no background refresh and nothing
 * is cached, since the user only needs this "to populate when [they want] to view
 * their library card info," not kept warm in the background.
 *
 * All state here is local to this screen rather than lifted into
 * PlayerDebugScreen, unlike Tags/Holds -- there's no sibling overlay screen that
 * needs to share any of it, so keeping the PIN-entry flow and the scraped list
 * self-contained is the simpler option. [LibraryCardPinStore.hasPin] is read once,
 * synchronously, before the first frame renders, so there is no flash of the
 * wrong gate state on open -- the main flicker risk this screen could otherwise
 * have (see the "no jumpy screen or flashing bugs" ask this shipped under).
 */
@Composable
internal fun LibraryCardScreen(onBack: () -> Unit) {
    var hasPin by remember { mutableStateOf(LibraryCardPinStore.hasPin()) }
    var unlocked by remember { mutableStateOf(false) }
    // Holds the first-entered PIN while setting one up for the first time, awaiting
    // a second matching entry -- cleared back to null on either a confirmed match
    // (see onDigit) or a mismatch.
    var pendingNewPin by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    // Persisted in LibraryCardPinStore, not just here -- reading it fresh on
    // open means a lockout from an earlier app session (or before the app was
    // force-closed) is still respected rather than silently reset.
    var lockoutRemainingMs by remember { mutableStateOf(LibraryCardPinStore.lockoutRemainingMillis()) }

    var cards by remember { mutableStateOf<List<LibraryCard>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refreshCards() {
        loading = true
        message = ""
        scope.launch {
            LibbyWebPlayer.loadLibraryCardsPage()
            delay(1000)
            // Unlike the other screens' scrapers, this one is itself a suspend
            // function rather than a callback -- it has to click through each
            // library's own "Manage Cards" button one at a time (see its doc
            // comment), not just read a single already-rendered page.
            val found = LibbyWebPlayer.scrapeLibraryCards()
            cards = found
            loading = false
            if (found.isEmpty()) message = "No library cards found."
        }
    }

    // Ticks the lockout countdown back down to 0 once a minute's worth of
    // waiting has actually passed, re-polling the persisted store rather than
    // just subtracting locally -- so it stays correct even if the screen was
    // backgrounded for a while. Restarts whenever a fresh lockout begins.
    LaunchedEffect(lockoutRemainingMs > 0) {
        while (lockoutRemainingMs > 0) {
            delay(1000)
            lockoutRemainingMs = LibraryCardPinStore.lockoutRemainingMillis()
        }
    }

    fun onDigit(digit: String) {
        if (hasPin && lockoutRemainingMs > 0) return
        if (entered.length >= 4) return
        val next = entered + digit
        entered = next
        error = ""
        if (next.length < 4) return

        if (!hasPin) {
            val firstEntry = pendingNewPin
            when {
                firstEntry == null -> {
                    pendingNewPin = next
                    entered = ""
                }
                firstEntry == next -> {
                    LibraryCardPinStore.setPin(next)
                    hasPin = true
                    pendingNewPin = null
                    entered = ""
                    unlocked = true
                    refreshCards()
                }
                else -> {
                    pendingNewPin = null
                    entered = ""
                    error = "PINs didn't match. Try again."
                }
            }
        } else if (LibraryCardPinStore.verifyPin(next)) {
            entered = ""
            LibraryCardPinStore.recordSuccess()
            lockoutRemainingMs = 0
            unlocked = true
            refreshCards()
        } else {
            entered = ""
            lockoutRemainingMs = LibraryCardPinStore.recordFailedAttempt()
            if (lockoutRemainingMs <= 0) error = "Incorrect PIN."
        }
    }

    fun onBackspace() {
        if (hasPin && lockoutRemainingMs > 0) return
        if (entered.isEmpty()) return
        entered = entered.dropLast(1)
        error = ""
    }

    LivrospeakSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("Library Cards"),
            )
            if (!unlocked) {
                PinGate(
                    settingUp = !hasPin,
                    confirming = !hasPin && pendingNewPin != null,
                    entered = entered,
                    error = error,
                    lockoutRemainingMs = if (hasPin) lockoutRemainingMs else 0L,
                    onDigit = ::onDigit,
                    onBackspace = ::onBackspace,
                    modifier = Modifier.weight(1f),
                )
            } else {
                when {
                    loading && cards.isEmpty() ->
                        LibraryCardsStatus("Loading library cards…", Modifier.weight(1f))
                    message.isNotBlank() && cards.isEmpty() ->
                        LibraryCardsStatus(message, Modifier.weight(1f))
                    cards.isEmpty() ->
                        LibraryCardsStatus("No library cards found.", Modifier.weight(1f))
                    else -> {
                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(start = 1.5f.gridUnitsAsDp(), top = 0.75f.gridUnitsAsDp()),
                        ) {
                            cards.forEach { card -> LibraryCardRow(card) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCardsStatus(message: String, modifier: Modifier = Modifier) {
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
private fun LibraryCardRow(card: LibraryCard) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 1.5f.gridUnitsAsDp(), end = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = card.libraryName,
            variant = LightTextVariant.Subheading,
        )
        LightText(
            text = card.cardNumber,
            variant = LightTextVariant.Heading,
            monospace = true,
        )
    }
}

/**
 * The PIN entry/setup UI itself. Interaction design (dot indicators that fill in
 * as digits are entered, a 3-column numeric keypad with backspace in the bottom
 * row) is adapted from a PIN screen in a separate LightOS-style project, but
 * rebuilt from Livrospeak's own [LightText]/[gridUnitsAsDp]/[lightClickable]
 * primitives and theme tokens rather than carrying over that project's own
 * theming or view-model plumbing, neither of which exist here.
 *
 * The error-message slot is a fixed-height [Box] that's always present, whether
 * or not there's currently an error -- so an incorrect PIN doesn't shift the
 * keypad up and down as the message appears and disappears underneath it.
 */
@Composable
private fun PinGate(
    settingUp: Boolean,
    confirming: Boolean,
    entered: String,
    error: String,
    lockoutRemainingMs: Long,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        LightText(
            text = when {
                lockoutRemainingMs > 0 -> "Too Many Attempts"
                confirming -> "Confirm PIN"
                settingUp -> "Set a PIN"
                else -> "Enter PIN"
            },
            variant = LightTextVariant.Heading,
        )
        Spacer(Modifier.height(1f.gridUnitsAsDp()))
        if (lockoutRemainingMs > 0) {
            // No keypad at all while locked out -- there's nothing useful to
            // tap, and hiding it is what actually makes the lockout mean
            // anything (see LibraryCardPinStore.recordFailedAttempt's doc
            // comment for why this exists). A PIN reset is still possible, but
            // only from Settings → Libby now, not from this screen.
            LightText(
                text = formatRemainingLockout(lockoutRemainingMs),
                variant = LightTextVariant.Paragraph,
                lighten = true,
            )
        } else {
            PinDots(entered = entered.length)
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
            Box(
                modifier = Modifier.height(2f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                if (error.isNotBlank()) {
                    LightText(text = error, variant = LightTextVariant.Detail, lighten = true)
                }
            }
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
            PinKeypad(onDigit = onDigit, onBackspace = onBackspace)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** "Try again in 4:32", rounded up to the next whole second so this never
 *  reads "0:00" while still technically locked. */
private fun formatRemainingLockout(remainingMs: Long): String {
    val totalSeconds = (remainingMs + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "Try again in %d:%02d".format(minutes, seconds)
}

@Composable
private fun PinDots(entered: Int, total: Int = 4) {
    val color = LightThemeTokens.colors.content
    Row(horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp())) {
        repeat(total) { index ->
            val filled = index < entered
            Box(
                modifier = Modifier
                    .size(0.75f.gridUnitsAsDp())
                    .clip(CircleShape)
                    .then(
                        if (filled) {
                            Modifier.background(color)
                        } else {
                            Modifier.border(1.dp, color, CircleShape)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun PinKeypad(onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫"),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2f.gridUnitsAsDp())) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .size(3.5f.gridUnitsAsDp())
                            .then(
                                if (key.isEmpty()) {
                                    Modifier
                                } else {
                                    Modifier.lightClickable(
                                        onClickLabel = if (key == "⌫") "Backspace" else "Digit $key",
                                        role = Role.Button,
                                        onClick = { if (key == "⌫") onBackspace() else onDigit(key) },
                                    )
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key.isNotEmpty()) {
                            LightText(text = key, variant = LightTextVariant.Heading)
                        }
                    }
                }
            }
            Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
        }
    }
}
