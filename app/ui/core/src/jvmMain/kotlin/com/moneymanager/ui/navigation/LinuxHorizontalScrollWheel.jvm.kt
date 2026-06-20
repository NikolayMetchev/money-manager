@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.moneymanager.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import kotlinx.coroutines.launch

private val isLinux: Boolean =
    System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)

// On Linux/X11 a horizontal wheel notch arrives as a raw AWT button press rather than a
// MouseWheelEvent: button 4 for one direction, button 5 for the other. Compose only tracks buttons
// 1-3, so it never delivers these through its pointer pipeline and a bound horizontalScroll never
// moves. We listen for 4/5 at the AWT level instead and scroll the hovered region ourselves.
// (Vertical scrolling is untouched: it arrives as a normal MouseWheelEvent that Compose handles.)
// Side buttons are 6/7 here, handled separately in MouseButtonNavigation.jvm.kt.
private const val AWT_BUTTON_SCROLL_LEFT = 4
private const val AWT_BUTTON_SCROLL_RIGHT = 5

// Pixels scrolled per wheel notch. Roughly a column's worth in the wide tables this is used on.
private const val SCROLL_STEP_PX = 120f

actual fun Modifier.linuxHorizontalScrollWheel(scrollState: ScrollState): Modifier =
    if (!isLinux) {
        // Windows/macOS deliver the horizontal wheel to Compose already; nothing to bridge.
        this
    } else {
        composed {
            var hovered by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            DisposableEffect(scrollState) {
                val toolkit = Toolkit.getDefaultToolkit()
                val listener =
                    AWTEventListener { event ->
                        if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED && hovered) {
                            val delta =
                                when (event.button) {
                                    AWT_BUTTON_SCROLL_LEFT -> -SCROLL_STEP_PX
                                    AWT_BUTTON_SCROLL_RIGHT -> SCROLL_STEP_PX
                                    else -> return@AWTEventListener
                                }
                            scope.launch { scrollState.scrollBy(delta) }
                        }
                    }
                toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
                onDispose { toolkit.removeAWTEventListener(listener) }
            }

            // Track hover so only the region under the cursor reacts to the wheel. Keep this outside
            // the horizontalScroll modifier so the hover bounds are the fixed viewport, not the wide
            // scrolled content.
            onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
        }
    }
