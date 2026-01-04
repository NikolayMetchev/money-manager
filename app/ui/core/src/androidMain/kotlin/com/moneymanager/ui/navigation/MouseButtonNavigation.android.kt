package com.moneymanager.ui.navigation

import androidx.compose.ui.Modifier

actual fun Modifier.mouseButtonNavigation(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier = this // No-op on Android - mouse buttons not available
