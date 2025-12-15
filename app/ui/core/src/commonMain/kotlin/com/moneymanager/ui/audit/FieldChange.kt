package com.moneymanager.ui.audit

/**
 * Represents the change state of a field in an audit entry.
 * Used to display diffs between audit entries in the changelog view.
 */
sealed class FieldChange<out T> {
    /**
     * Field value changed from [oldValue] to [newValue].
     */
    data class Changed<T>(val oldValue: T, val newValue: T) : FieldChange<T>()

    /**
     * Field value remained the same.
     */
    data class Unchanged<T>(val value: T) : FieldChange<T>()

    /**
     * Field was created with this initial [value] (INSERT operation).
     */
    data class Created<T>(val value: T) : FieldChange<T>()

    /**
     * Field was deleted with this final [value] (DELETE operation).
     */
    data class Deleted<T>(val value: T) : FieldChange<T>()

    /**
     * Gets the current/new value from any change type.
     */
    fun value(): T =
        when (this) {
            is Changed -> newValue
            is Unchanged -> value
            is Created -> value
            is Deleted -> value
        }
}
