@file:Suppress("UNUSED_PARAMETER")

package com.moneymanager.ui.navigation

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit
