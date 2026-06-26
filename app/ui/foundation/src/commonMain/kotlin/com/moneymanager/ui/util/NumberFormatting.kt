package com.moneymanager.ui.util

/** Formats an integer with locale-aware grouping separators (e.g. "1,234,567"). */
expect fun formatGroupedNumber(value: Long): String
