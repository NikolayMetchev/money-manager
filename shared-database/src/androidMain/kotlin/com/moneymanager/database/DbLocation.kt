package com.moneymanager.database

actual data class DbLocation(val name: String?) {
    actual fun exists() = true

    actual fun isInMemory() = name == null
}

/**
 * In-memory database location for testing.
 * When name is null, AndroidSqliteDriver creates an in-memory database.
 */
actual val IN_MEMORY_DATABASE: DbLocation = DbLocation(null)
