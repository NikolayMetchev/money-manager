package com.moneymanager.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier

// No-op on Android - no mouse wheel; scrollState is part of the expect signature.
@Suppress("UnusedParameter")
actual fun Modifier.linuxHorizontalScrollWheel(scrollState: ScrollState): Modifier = this
