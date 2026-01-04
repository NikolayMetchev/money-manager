package com.moneymanager.ui.navigation

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // No-op on desktop - mouse back button is handled by mouseButtonNavigation
}
