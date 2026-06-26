package com.moneymanager.ui.error

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global state holder for schema errors detected anywhere in the application.
 * Used by uncaught exception handlers to report schema errors, which are then
 * observed by MoneyManagerApp to display the DatabaseSchemaErrorDialog.
 */
object GlobalSchemaErrorState {
    private val _schemaError = MutableStateFlow<SchemaErrorInfo?>(null)
    val schemaError: StateFlow<SchemaErrorInfo?> = _schemaError.asStateFlow()

    /**
     * Reports a schema error to be displayed globally.
     * @param databaseLocation The path/location of the database that caused the error
     * @param error The exception that was thrown
     */
    fun reportError(
        databaseLocation: String,
        error: Throwable,
    ) {
        _schemaError.value = SchemaErrorInfo(databaseLocation, error)
    }

    /**
     * Clears the current error state, typically called after user dismisses the dialog.
     */
    fun clearError() {
        _schemaError.value = null
    }
}

/**
 * Data class holding schema error information.
 */
data class SchemaErrorInfo(
    val databaseLocation: String,
    val error: Throwable,
)
