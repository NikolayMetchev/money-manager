package com.moneymanager.database

/**
 * Manages the lifecycle of MoneyManager databases.
 * Platform-specific implementations handle database creation, opening, and closing.
 */
interface DatabaseManager {
    /**
     * Creates a new database at the specified location.
     * If a database already exists at this location, it will be opened instead.
     *
     * @param location Platform-specific database location (path on JVM, name on Android)
     * @return The created or opened database instance
     * @throws Exception if database creation fails
     */
    suspend fun createDatabase(location: DbLocation): MoneyManagerDatabase

    /**
     * Opens an existing database at the specified location.
     * If the database doesn't exist, creates a new one.
     *
     * @param location Platform-specific database location
     * @return The opened database instance
     * @throws Exception if database opening fails
     */
    suspend fun openDatabase(location: DbLocation): MoneyManagerDatabase

    /**
     * Closes the currently active database if any.
     * After closing, a new database must be opened before using repositories.
     */
    suspend fun closeDatabase()

    /**
     * Checks if a database exists at the specified location.
     *
     * @param location Platform-specific database location
     * @return true if the database exists, false otherwise
     */
    suspend fun databaseExists(location: DbLocation): Boolean

    /**
     * Returns the default database location for the platform.
     * - JVM: ~/.moneymanager/default.db
     * - Android: "money_manager.db"
     *
     * @return The default database location
     */
    fun getDefaultLocation(): DbLocation
}
