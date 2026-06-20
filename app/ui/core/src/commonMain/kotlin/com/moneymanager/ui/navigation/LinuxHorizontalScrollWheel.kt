package com.moneymanager.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier

/**
 * Makes the horizontal scroll wheel (or the wheel-tilt left/right gesture) scroll [scrollState]
 * while the pointer is over the modified element.
 *
 * This only exists to work around Linux/X11. There, this particular mouse delivers a horizontal
 * wheel notch as a raw AWT button 4/5 press (not a [java.awt.event.MouseWheelEvent]), so Compose —
 * which only tracks buttons 1-3 — drops it entirely and the bound [scrollState] never moves.
 * Vertical scrolling is unaffected because it arrives as a normal `MouseWheelEvent`. The JVM
 * implementation bridges those raw button presses into a horizontal scroll (see
 * LinuxHorizontalScrollWheel.jvm.kt).
 *
 * On Windows/macOS the horizontal wheel already reaches Compose, so the JVM implementation is a
 * no-op there. On Android there is no mouse wheel, so this is a no-op too.
 */
expect fun Modifier.linuxHorizontalScrollWheel(scrollState: ScrollState): Modifier
