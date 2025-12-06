package com.moneymanager.database

import com.moneymanager.database.sql.MoneyManagerDatabase

/**
 * Default database filename used across all platforms.
 */
const val DEFAULT_DATABASE_NAME = "money_manager.db"

/**
 * Manages MoneyManager databases.
 * Platform-specific implementations handle database opening.
 * Implementations are stateless - they do not track open databases.
 */
interface DatabaseManager {
    /**
     * Opens a database at the specified location.
     * If the database doesn't exist, creates a new one.
     *
     * @param location Platform-specific database location (path on JVM, name on Android)
     * @return The opened database instance
     * @throws Exception if database opening fails
     */
    suspend fun openDatabase(location: DbLocation): MoneyManagerDatabase

    /**
     * Checks if a database exists at the specified location.
     *
     * @param location Platform-specific database location
     * @return true if the database exists, false otherwise
     */
    suspend fun databaseExists(location: DbLocation): Boolean

    /**
     * Returns the default database location for the platform.
     * - JVM: ~/.moneymanager/money_manager.db
     * - Android: "money_manager.db"
     *
     * @return The default database location
     */
    fun getDefaultLocation(): DbLocation

    /**
     * Backs up the database at the specified location by renaming it with a .backup extension.
     * If a backup already exists, it will be overwritten.
     *
     * @param location Platform-specific database location to backup
     * @return The location of the backup file
     * @throws Exception if backup fails
     */
    suspend fun backupDatabase(location: DbLocation): DbLocation

    /**
     * Deletes the database at the specified location.
     *
     * @param location Platform-specific database location to delete
     * @throws Exception if deletion fails
     */
    suspend fun deleteDatabase(location: DbLocation)
}
