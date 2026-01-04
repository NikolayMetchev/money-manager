package com.moneymanager.ui.navigation

import androidx.compose.ui.Modifier

/**
 * Adds mouse back/forward button support for navigation.
 * On desktop, handles mouse button 4 (back) and button 5 (forward).
 * On Android, this is a no-op since mouse buttons aren't available.
 */
expect fun Modifier.mouseButtonNavigation(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier
