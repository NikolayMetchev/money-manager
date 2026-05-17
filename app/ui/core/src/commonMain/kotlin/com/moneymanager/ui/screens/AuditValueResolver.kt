package com.moneymanager.ui.screens

internal fun <T> resolveUpdateValue(
    index: Int,
    currentValue: T?,
    previousValue: T?,
    entryValue: T,
): T =
    when {
        index == 0 && currentValue != null -> currentValue
        index > 0 && previousValue != null -> previousValue
        else -> entryValue
    }
