package com.moneymanager.ui.navigation

import androidx.compose.ui.Modifier

// The browser's own history handles the back and forward buttons.
actual fun Modifier.mouseButtonNavigation(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier = this
