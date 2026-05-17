package com.moneymanager.ui.navigation

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    if (enabled) {
        // JVM desktop has no platform back gesture; keep signature usage explicit for expect/actual parity.
        onBack.hashCode()
    }
}
