package com.moneymanager.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier

// The browser reports a horizontal wheel as one, on every OS.
@Suppress("UnusedParameter")
actual fun Modifier.linuxHorizontalScrollWheel(scrollState: ScrollState): Modifier = this
