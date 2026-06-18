package com.moneymanager.database

import com.moneymanager.domain.model.DbLocation

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
    suspend fun openDatabase(location: DbLocation): MoneyManagerDatabaseWrapper

    /**
     * Opens a database and reports progress for user-visible startup feedback.
     * Implementations that do not have granular progress can rely on the default behavior.
     */
    suspend fun openDatabaseWithProgress(
        location: DbLocation,
        onProgress: (DatabaseInitializationProgress) -> Unit,
    ): MoneyManagerDatabaseWrapper = openDatabase(location)

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

    /**
     * Produces a fully-compacted, self-contained snapshot of [database] and returns its raw bytes.
     *
     * Implemented with SQLite `VACUUM INTO`, so the result is a single consistent database file with
     * no separate WAL/SHM sidecars, regardless of the live database's current WAL state. Callers are
     * responsible for any pre-shrink steps (e.g. [DatabaseMaintenanceService.truncateMaterializedViews]).
     *
     * @return the bytes of the snapshot database file
     */
    suspend fun snapshot(database: MoneyManagerDatabaseWrapper): ByteArray

    /**
     * Overwrites the database file at [location] with [bytes] (a snapshot produced by [snapshot]),
     * removing any stale WAL/SHM sidecars first.
     *
     * Must be called while no database is open at [location]; the caller opens it afterwards (which,
     * for a rehydrated remote database, should be followed by
     * [DatabaseMaintenanceService.fullRefreshMaterializedViews]).
     */
    suspend fun restore(location: DbLocation, bytes: ByteArray)
}

data class DatabaseInitializationProgress(
    val text: String,
    val completedSteps: Int,
    val totalSteps: Int,
) {
    val fraction: Float
        get() = if (totalSteps > 0) completedSteps.toFloat() / totalSteps.toFloat() else 0f
}
