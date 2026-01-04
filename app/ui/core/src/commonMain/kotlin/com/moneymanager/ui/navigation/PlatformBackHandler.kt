package com.moneymanager.ui.navigation

import androidx.compose.runtime.Composable

/**
 * Platform-specific back button handler.
 * On Android, handles the system back button/gesture.
 * On desktop, this is a no-op since mouse back button is handled separately.
 *
 * @param enabled Whether the back handler should be active
 * @param onBack Callback invoked when back is pressed
 */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
