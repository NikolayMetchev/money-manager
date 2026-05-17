package com.moneymanager.ui.screens

import com.moneymanager.ui.audit.FieldChange

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

internal fun <T> changedOrUnchanged(
    oldValue: T,
    newValue: T,
): FieldChange<T> =
    if (oldValue != newValue) {
        FieldChange.Changed(oldValue, newValue)
    } else {
        FieldChange.Unchanged(oldValue)
    }

internal inline fun <T, E> resolveUpdateChange(
    index: Int,
    currentValue: T?,
    previousEntry: E?,
    entryValue: T,
    previousValue: (E) -> T,
): FieldChange<T> =
    changedOrUnchanged(
        oldValue = entryValue,
        newValue =
            resolveUpdateValue(
                index = index,
                currentValue = currentValue,
                previousValue = previousEntry?.let(previousValue),
                entryValue = entryValue,
            ),
    )
