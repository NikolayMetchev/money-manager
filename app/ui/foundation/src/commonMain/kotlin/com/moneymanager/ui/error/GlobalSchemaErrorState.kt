package com.moneymanager.ui.error

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global state holder for schema errors detected anywhere in the application.
 * Used by uncaught exception handlers to report schema errors, which are then
 * observed by MoneyManagerApp to display the DatabaseSchemaErrorDialog.
 *
 * Only the error itself is carried: the observer already knows which database is open, and resolves
 * the location to show from that.
 */
object GlobalSchemaErrorState {
    private val _schemaError = MutableStateFlow<Throwable?>(null)
    val schemaError: StateFlow<Throwable?> = _schemaError.asStateFlow()

    /** Reports a schema [error] to be displayed globally. */
    fun reportError(error: Throwable) {
        _schemaError.value = error
    }

    /**
     * Clears the current error state, typically called after user dismisses the dialog.
     */
    fun clearError() {
        _schemaError.value = null
    }
}
