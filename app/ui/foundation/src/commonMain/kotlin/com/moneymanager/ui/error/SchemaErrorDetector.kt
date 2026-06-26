package com.moneymanager.ui.error

/**
 * Utility object for detecting if an exception is a SQLite schema error.
 * Schema errors occur when the database structure doesn't match what the code expects,
 * such as missing tables, views, or columns.
 */
object SchemaErrorDetector {
    /**
     * Checks if the given throwable is a SQLite schema error.
     * @param throwable The exception to check
     * @return true if this appears to be a schema error, false otherwise
     */
    fun isSchemaError(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase() ?: return false
        return message.contains("no such table") ||
            message.contains("no such view") ||
            message.contains("no such column") ||
            message.contains("no such index")
    }
}
