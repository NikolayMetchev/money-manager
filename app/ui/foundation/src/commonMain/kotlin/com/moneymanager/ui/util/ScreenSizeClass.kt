package com.moneymanager.ui.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen size classification for responsive layouts.
 * Based on Material Design 3 window size classes.
 */
enum class ScreenSizeClass {
    /** Compact: phones in portrait (width < 600dp) */
    Compact,

    /** Medium: tablets in portrait, foldables (600dp <= width < 840dp) */
    Medium,

    /** Expanded: tablets in landscape, desktops (width >= 840dp) */
    Expanded,
    ;

    companion object {
        fun fromWidth(width: Dp): ScreenSizeClass =
            when {
                width < 600.dp -> Compact
                width < 840.dp -> Medium
                else -> Expanded
            }
    }
}
