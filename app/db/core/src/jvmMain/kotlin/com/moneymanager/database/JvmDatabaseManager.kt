package com.moneymanager.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.moneymanager.database.sql.MoneyManagerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.exists

private val DbLocation.jdbcUrl: String
    get() = "jdbc:sqlite:$path"

/**
 * JVM implementation of DatabaseManager.
 * Manages SQLite database files on the file system.
 * Stateless - does not track open databases.
 */
class JvmDatabaseManager : DatabaseManager {
    override suspend fun openDatabase(location: DbLocation): MoneyManagerDatabase =
        withContext(Dispatchers.IO) {
            // Auto-detect if this is a new database
            val isNewDatabase = !location.exists()

            // Create parent directories if they don't exist
            location.path.parent?.let { parentDir ->
                if (!parentDir.exists()) {
                    Files.createDirectories(parentDir)
                }
            }

            // Create driver with JDBC URL and properties to enable foreign keys
            // Note: foreign_keys must be set via Properties for file-based databases
            // See: https://github.com/sqldelight/sqldelight/issues/2421
            val properties =
                Properties().apply {
                    put("foreign_keys", "true")
                }
            val driver = JdbcSqliteDriver(location.jdbcUrl, properties)

            // Apply additional connection-level PRAGMA settings (if any beyond foreign_keys)
            // Note: foreign_keys is already enabled via Properties above
            DatabaseConfig.connectionPragmas
                .filterNot { it.contains("foreign_keys", ignoreCase = true) }
                .forEach { pragma ->
                    driver.execute(null, pragma, 0)
                }

            if (isNewDatabase) {
                // Create schema for new database
                MoneyManagerDatabase.Schema.create(driver)
            }

            val database = MoneyManagerDatabase(driver)

            if (isNewDatabase) {
                DatabaseConfig.seedDatabase(database, driver)
            }

            database
        }

    override suspend fun databaseExists(location: DbLocation): Boolean =
        withContext(Dispatchers.IO) {
            location.exists()
        }

    override fun getDefaultLocation() = DEFAULT_DATABASE_PATH

    override suspend fun backupDatabase(location: DbLocation): DbLocation =
        withContext(Dispatchers.IO) {
            if (!location.exists()) {
                throw IllegalArgumentException("Database does not exist at: $location")
            }

            val backupPath = location.path.resolveSibling("${location.path.fileName}.backup")
            val backupLocation = DbLocation(backupPath)

            // Delete existing backup if it exists
            if (backupLocation.exists()) {
                Files.delete(backupLocation.path)
            }

            // Move the database file to backup
            Files.move(location.path, backupLocation.path)

            backupLocation
        }

    override suspend fun deleteDatabase(location: DbLocation): Unit =
        withContext(Dispatchers.IO) {
            if (location.exists()) {
                Files.delete(location.path)
            }
        }
}
