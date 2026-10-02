package com.tyshi00.livrospeak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.thelightphone.lp3Keyboard.ui.DarkKeyboardColors
import com.thelightphone.lp3Keyboard.ui.LightKeyboardColors
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardTheme
import com.thelightphone.lp3Keyboard.ui.LocalKeyboardColors
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardWrapper
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel

/**
 * Exact Light SDK embedded LP3 keyboard wrapper, adapted only to Livrospeak's package.
 * As of lp3keyboard 0.0.19, [Lp3KeyboardViewModel] itself already implements both the
 * regular key callback and the swipe callback (see [Lp3KeyboardWrapper]'s single-arg
 * overload), so this no longer needs to collect layout/keyboardOptions/layoutOptions
 * itself and pass them through by hand -- that overload does it internally.
 */
@Composable
fun LightEmbeddedLp3Keyboard(viewModel: Lp3KeyboardViewModel<*>) {
    val keyboardColors = if (LightThemeTokens.colors == LightThemeColors.Light) {
        LightKeyboardColors
    } else {
        DarkKeyboardColors
    }
    val systemDensity = LocalDensity.current
    val keyboardDensity = Density(
        density = maxOf(systemDensity.density, 3f),
        fontScale = systemDensity.fontScale,
    )
    CompositionLocalProvider(LocalDensity provides keyboardDensity) {
        Lp3KeyboardTheme(keyboardColors) {
            val colors = LocalKeyboardColors.current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(top = 10.dp),
            ) {
                Lp3KeyboardWrapper(viewModel = viewModel)
            }
        }
    }
}
