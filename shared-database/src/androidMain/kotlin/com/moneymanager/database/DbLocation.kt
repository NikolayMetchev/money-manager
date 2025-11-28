package com.moneymanager.database

actual data class DbLocation(val name: String?) {
    /**
     * On Android, checking file existence requires Context.
     * Use [DatabaseManager.databaseExists] for actual file existence checks.
     * This returns true for non-memory databases (assumed to exist or will be created).
     */
    actual fun exists() = !isInMemory()

    actual fun isInMemory() = name == null
}

/**
 * In-memory database location for testing.
 * When name is null, AndroidSqliteDriver creates an in-memory database.
 */
actual val IN_MEMORY_DATABASE: DbLocation = DbLocation(null)
