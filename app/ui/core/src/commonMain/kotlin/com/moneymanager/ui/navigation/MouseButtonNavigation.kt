package com.moneymanager.ui.navigation

import androidx.compose.ui.Modifier

/**
 * Adds mouse back/forward button support for navigation.
 *
 * On desktop the contract is "navigate on the physical back/forward side buttons", but the
 * underlying AWT button numbers differ by platform: Windows/macOS report them as buttons 4/5,
 * while Linux (X11/XWayland) reserves 4-7 for scroll axes and reports the side buttons as 6/7.
 * The JVM implementation handles this difference (see MouseButtonNavigation.jvm.kt).
 *
 * On Android, this is a no-op since mouse buttons aren't available.
 */
expect fun Modifier.mouseButtonNavigation(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier
