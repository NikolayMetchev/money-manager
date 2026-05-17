package com.moneymanager.ui.screens

import com.moneymanager.ui.audit.FieldChange

private data class ResolvedValue<T>(
    val value: T,
)

private fun <T> resolveUpdateValue(
    index: Int,
    currentValue: ResolvedValue<T>?,
    previousValue: ResolvedValue<T>?,
    entryValue: T,
): T =
    when {
        index == 0 && currentValue != null -> currentValue.value
        index > 0 && previousValue != null -> previousValue.value
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

internal fun <T, C, E> resolveUpdateChange(
    index: Int,
    currentEntry: C?,
    previousEntry: E?,
    entryValue: T,
    currentValue: (C) -> T,
    previousValue: (E) -> T,
): FieldChange<T> =
    changedOrUnchanged(
        oldValue = entryValue,
        newValue =
            resolveUpdateValue(
                index = index,
                currentValue = currentEntry?.let { ResolvedValue(currentValue(it)) },
                previousValue = previousEntry?.let { ResolvedValue(previousValue(it)) },
                entryValue = entryValue,
            ),
    )

internal fun <T, E> resolveUpdateChange(
    index: Int,
    previousEntry: E?,
    entryValue: T,
    previousValue: (E) -> T,
): FieldChange<T> =
    changedOrUnchanged(
        oldValue = entryValue,
        newValue =
            resolveUpdateValue(
                index = index,
                currentValue = null,
                previousValue = previousEntry?.let { ResolvedValue(previousValue(it)) },
                entryValue = entryValue,
            ),
    )
