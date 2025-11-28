package com.moneymanager.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.io.path.exists

/**
 * JVM implementation of DatabaseManager.
 * Manages SQLite database files on the file system.
 */
class JvmDatabaseManager : DatabaseManager {
    private var currentDatabase: MoneyManagerDatabase? = null

    override suspend fun createDatabase(location: DbLocation): MoneyManagerDatabase =
        withContext(Dispatchers.IO) {
            // Close any existing database first
            closeDatabase()

            // Handle in-memory database (null path)
            val jdbcUrl =
                if (location.path == null) {
                    "jdbc:sqlite::memory:"
                } else {
                    "jdbc:sqlite:${location.path}"
                }

            // Auto-detect if this is a new database
            val isNewDatabase = location.path == null || !location.path.exists()

            if (isNewDatabase && location.path != null) {
                // Create parent directories if they don't exist
                location.path.parent?.let { parentDir ->
                    if (!parentDir.exists()) {
                        Files.createDirectories(parentDir)
                    }
                }
            }

            // Create driver with JDBC URL
            val driver = JdbcSqliteDriver(jdbcUrl)

            if (isNewDatabase) {
                // Create schema for new database
                MoneyManagerDatabase.Schema.create(driver)
            }

            // Create and cache the database instance
            val database = MoneyManagerDatabase(driver)
            currentDatabase = database
            database
        }

    override suspend fun openDatabase(location: DbLocation): MoneyManagerDatabase {
        // openDatabase and createDatabase have the same logic on JVM
        // The driver will create the file if it doesn't exist
        return createDatabase(location)
    }

    override suspend fun closeDatabase() {
        currentDatabase?.let { db ->
            // SQLDelight doesn't provide a direct close method on the database
            // The driver should be closed, but we don't have direct access to it here
            // For now, just clear the reference
            currentDatabase = null
        }
    }

    override suspend fun databaseExists(location: DbLocation): Boolean =
        withContext(Dispatchers.IO) {
            location.exists()
        }

    override fun getDefaultLocation(): DbLocation {
        return DEFAULT_DATABASE_PATH
    }
}
