package com.tyshi00.livrospeak.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role

/**
 * Makes content clickable without displaying a visual press indication, and gives a
 * short vibration on every tap. This is the one shared primitive nearly every tappable
 * element in Livrospeak routes through (bar/bottom-bar buttons, top bar center, toggle rows,
 * shelf rows, search result rows, settings rows, the speed picker, ...), so wiring the
 * haptic in here -- rather than at each call site -- gives the whole app consistent
 * "yes, I felt that tap" feedback in one place. See [LivrospeakHaptics] for the vibration
 * itself, which the keyboard (`LightUrlInputEditor.kt`) also shares.
 */
fun Modifier.lightClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = {
            LivrospeakHaptics.click(context)
            onClick()
        },
    )
}

/**
 * Short vibration for on-screen taps and keyboard presses alike. Plain Android
 * Vibrator API (VibratorManager + a one-shot VibrationEffect) -- no extra dependency
 * needed, and fully available since API 31 (Livrospeak's minSdk is 33). Unlike Light's own
 * SDK, this always vibrates rather than checking the phone's system-wide haptics
 * toggle, since reading that requires binding to a private LP3 system service through
 * their fuller SDK (LightActivity + sdk:client/sdk:shared), which Livrospeak doesn't
 * currently depend on -- not worth pulling in for a single settings flag.
 */
object LivrospeakHaptics {
    private const val CLICK_DURATION_MILLISECONDS = 45L

    fun click(context: Context) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(
            VibrationEffect.createOneShot(CLICK_DURATION_MILLISECONDS, VibrationEffect.DEFAULT_AMPLITUDE),
        )
    }
}
