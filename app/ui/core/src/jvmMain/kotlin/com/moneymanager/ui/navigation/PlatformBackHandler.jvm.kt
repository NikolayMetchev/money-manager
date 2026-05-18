package com.moneymanager.ui.navigation

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    if (!enabled) return
    onBack.hashCode()
    // JVM desktop has no platform back gesture.
}
