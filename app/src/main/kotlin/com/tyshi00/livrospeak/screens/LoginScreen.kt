package com.tyshi00.livrospeak.screens

import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.tyshi00.livrospeak.LibbyWebPlayer

/** Attaches the application-scoped Libby WebView directly to the Compose tree. */
@Composable
fun LoginScreen() {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            LibbyWebPlayer.detachFromCurrentParent()
            LibbyWebPlayer.attachToActivity(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )

                // Libby scrolls nested elements instead of the document itself.
                // Ensure Compose or another parent never steals those gestures.
                setOnTouchListener { view, event ->
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    if (
                        event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }

                requestFocus()
            }
        },
        update = { webView: WebView ->
            webView.requestFocus()
            webView.requestLayout()
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            LibbyWebPlayer.requireWebView().setOnTouchListener(null)
            LibbyWebPlayer.detachFromCurrentParent()
        }
    }
}
