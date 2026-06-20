package com.moneymanager.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier

actual fun Modifier.linuxHorizontalScrollWheel(scrollState: ScrollState): Modifier = this // No-op on Android - no mouse wheel
