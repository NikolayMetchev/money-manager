@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.moneymanager.ui.navigation

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent

private val isLinux: Boolean =
    System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)

// On Linux (X11/XWayland) the physical back/forward side buttons arrive as AWT buttons 6/7
// (buttons 4/5 are the horizontal scroll wheel). Compose only tracks buttons 1-3 as "pressed",
// so it never dispatches 6/7 through its pointer pipeline and a Modifier.onPointerEvent never
// sees them. We therefore listen at the AWT level on Linux instead.
// See https://github.com/JetBrains/compose-multiplatform/issues/2058
private const val AWT_BUTTON_BACK = 6
private const val AWT_BUTTON_FORWARD = 7

actual fun Modifier.mouseButtonNavigation(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier =
    if (isLinux) {
        // A global AWT listener catches the side-button presses Compose drops, app-wide.
        composed {
            val latestOnBack by rememberUpdatedState(onBack)
            val latestOnForward by rememberUpdatedState(onForward)
            DisposableEffect(Unit) {
                val toolkit = Toolkit.getDefaultToolkit()
                val listener =
                    AWTEventListener { event ->
                        if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED) {
                            when (event.button) {
                                AWT_BUTTON_BACK -> latestOnBack()
                                AWT_BUTTON_FORWARD -> latestOnForward()
                                else -> {}
                            }
                        }
                    }
                toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
                onDispose { toolkit.removeAWTEventListener(listener) }
            }
            this
        }
    } else {
        // Windows/macOS deliver back/forward correctly through Compose. Listen on the Initial pass
        // so the press is handled at the root before a child (e.g. a clickable card) consumes it.
        onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
            when (event.button) {
                PointerButton.Back -> onBack()
                PointerButton.Forward -> onForward()
                else -> {}
            }
        }
    }
