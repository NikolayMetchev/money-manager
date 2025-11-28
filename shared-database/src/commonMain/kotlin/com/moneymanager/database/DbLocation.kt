package com.moneymanager.database

expect class DbLocation {
    fun exists(): Boolean

    fun isInMemory(): Boolean
}

/**
 * In-memory database location for testing.
 */
expect val IN_MEMORY_DATABASE: DbLocation
