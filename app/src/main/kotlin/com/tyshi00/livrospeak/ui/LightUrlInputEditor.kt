package com.tyshi00.livrospeak.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.layout.EnShared
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `viewModel(key = ..., factory = ...)` below only runs [keyboardFactory] the FIRST
 * time a given [LightUrlInputEditor] `keyboardKey` is requested from the host
 * Activity's ViewModelStore -- every later mount with the same key (e.g. editing a
 * query again after backing out of a failed search) gets back the ORIGINAL cached
 * `EnQwertyLp3KeyboardViewModel`, still wired to the ORIGINAL mount's callback. Without
 * this indirection that stale callback keeps writing to a detached copy of the old
 * screen's state: the visible text looks frozen even though a value is quietly
 * changing somewhere no longer on screen. Each mount republishes its live read/write
 * functions here on every recomposition (see the `SideEffect` below), so the one
 * long-lived callback object always forwards to whichever screen is actually showing
 * for that key.
 */
private val activeUrlEditors = mutableMapOf<String, Pair<() -> String, (String) -> Unit>>()

@Composable
fun LightUrlInputEditor(
    value: String,
    onValueChange: (String) -> Unit,
    message: String,
    submitting: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
    title: String = "Add Feed",
    doneLabel: String = "DONE",
    submittingLabel: String = "ADDING…",
    keyboardKey: String = "LivrospeakAddFeedKeyboard",
    backContentDescription: String = "Back to RSS Feeds",
    underline: Boolean = true,
) {
    val currentValue = remember { mutableStateOf(value) }
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    SideEffect {
        activeUrlEditors[keyboardKey] = Pair(
            { currentValue.value },
            { newValue: String ->
                currentValue.value = newValue
                latestOnValueChange.value(newValue)
            },
        )
    }
    val context = LocalContext.current
    val callback = remember(keyboardKey) {
        UrlKeyboardCallback(
            value = { activeUrlEditors[keyboardKey]?.first?.invoke().orEmpty() },
            update = { activeUrlEditors[keyboardKey]?.second?.invoke(it) },
        )
    }
    val options = remember {
        MutableStateFlow(
            KeyboardOptions(
                emojis = emptyList(),
                displayReturn = false,
                displayVoice = false,
                // The real Light keyboard doesn't scale/lift keys on tap -- this was
                // already true before this file's rewrite, so it wasn't caused by the
                // version bump, but it's wrong regardless and worth turning off now
                // that it's actually visible (taps mostly weren't registering before).
                enableKeyAnimation = false,
                swipeEnabled = false,
            ),
        )
    }
    val keyboard: EnQwertyLp3KeyboardViewModel<Unit> = viewModel(
        key = keyboardKey,
        factory = keyboardFactory(
            callback = callback,
            haptic = { LivrospeakHaptics.click(context) },
            options = options,
        ),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = backContentDescription,
            ),
            center = LightTopBarCenter.Text(title),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2f.gridUnitsAsDp()),
        ) {
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
            // A trailing "|" cursor gives visible feedback for every keystroke
            // (including space, which is otherwise invisible at the end of
            // left-aligned text with no cursor drawn).
            LightText(
                text = currentValue.value + "|",
                variant = LightTextVariant.Subheading,
                underline = underline,
                modifier = Modifier.fillMaxWidth(),
            )
            if (message.isNotBlank()) {
                Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                LightText(message, variant = LightTextVariant.Detail, lighten = true)
            }
        }
        LightEmbeddedLp3Keyboard(keyboard)
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = if (submitting) submittingLabel else doneLabel,
                    onClick = if (submitting) ({}) else onDone,
                ),
            ),
        )
    }
}

/**
 * Space is added the moment the key goes down, then again every repeat while it is
 * held, exactly like Light's own reference handler (`TextFieldKeyboardCallback`, which
 * ships with the keyboard library and is what the Light SDK's text editor uses). It is
 * not added on release or at the long-press point.
 *
 * How light-keyboard 0.0.21 reports a Space touch (`keyInput` in Lp3Keyboard.kt and
 * `EnBaseViewModel`, read from the library's source):
 *  - finger down: `onSpecialKeyPressed` (always, for every touch)
 *  - lifted before the long-press timeout: `onSpecialKeyReleased`
 *  - held past it: `onSpecialKeyLongPressed`, then `onSpecialKeyRepeated` every
 *    350 ms, and the eventual release is swallowed
 * Adding the space on release (as this file did before) made it depend on how long
 * the finger stayed down and on the lift being reported; adding it on press does not.
 *
 * Letters and Backspace are unchanged.
 *
 * Haptics are not triggered from here: `EnQwertyLp3KeyboardViewModel`'s `haptic`
 * constructor parameter (wired in [keyboardFactory] below) is what
 * `EnBaseViewModel.onKeyPressed`/`onSpecialKeyPressed` call before forwarding to this
 * delegate, so calling it again here would double the vibration per key.
 */
private class UrlKeyboardCallback(
    private val value: () -> String,
    private val update: (String) -> Unit,
) : Lp3RepeatableKeyboardCallback {
    override fun onKeyPressed(code: Int) = Unit
    override fun onKeyReleased(code: Int) = insert(code)
    // A tap whose press-to-release timing happens to land right on Android's
    // long-press threshold (viewConfiguration.longPressTimeoutMillis, ~500ms) fires
    // this INSTEAD of onKeyReleased -- EnBaseViewModel.onKeyReleased swallows the
    // release entirely once a long-press has been registered for that code (see
    // heldKeys.remove(code)?.apply { cancel(); return } there). Without inserting
    // here too, a tap in that narrow window between "too short to repeat" and "long
    // enough to swallow the release" produced nothing at all -- this is what made
    // typing feel like it dropped some keystrokes at random.
    override fun onKeyLongPressed(code: Int) = insert(code)
    override fun onKeyRepeated(code: Int) = insert(code)
    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) update(value() + " ")
    }
    // Backspace only: Space was already added when the key went down. Backspace
    // deletes on release, or once at the long-press point (EnBaseViewModel swallows
    // the release after a long press).
    override fun onSpecialKeyLongPressed(key: SpecialKey) = performSpecialKeyAction(key)
    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) update(value() + " ")
    }
    override fun onSpecialKeyReleased(key: SpecialKey) = performSpecialKeyAction(key)
    // Swipe typing is off for every Livrospeak keyboard (swipeEnabled = false, no
    // swipe callback), so this never fires, but the interface requires it.
    override fun onSubmitWord(word: CharSequence) = Unit
    private fun performSpecialKeyAction(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> update(value().dropLast(1))
            else -> Unit
        }
    }
    private fun insert(code: Int) = update(value() + buildString { appendCodePoint(code) })
}

private fun keyboardFactory(
    callback: Lp3RepeatableKeyboardCallback,
    haptic: () -> Unit,
    options: MutableStateFlow<KeyboardOptions>,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        EnQwertyLp3KeyboardViewModel<Unit>(
            passedCallback = callback,
            haptic = haptic,
            keyboardOptionsFlow = options,
            optionsForLayout = { layout ->
                LayoutOptions(
                    // Deliberately not an exhaustive `when` over Layout: it's a sealed
                    // interface implemented by every locale's layouts (En/Fr/Be/...),
                    // not just EnQwerty/EnShared, so only checking the two cases that
                    // actually need a close button here is what stays correct if
                    // another locale is added later.
                    displayCloseButton = when (layout) {
                        is EnShared.EmojiLayout, is EnShared.ExtendedCharKeyboard -> true
                        else -> false
                    },
                )
            },
        ) as T
}
