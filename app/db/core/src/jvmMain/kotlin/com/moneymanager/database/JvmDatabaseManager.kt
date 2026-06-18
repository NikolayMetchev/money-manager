package com.moneymanager.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.DEFAULT_DATABASE_PATH
import com.moneymanager.domain.model.DbLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Paths
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
    override suspend fun openDatabase(location: DbLocation): MoneyManagerDatabaseWrapper = openDatabaseWithProgress(location) {}

    override suspend fun openDatabaseWithProgress(
        location: DbLocation,
        onProgress: (DatabaseInitializationProgress) -> Unit,
    ): MoneyManagerDatabaseWrapper =
        withContext(Dispatchers.IO) {
            onProgress(DatabaseInitializationProgress("Checking for an existing database...", 1, 7))
            // Auto-detect if this is a new database
            val isNewDatabase = !location.exists()

            onProgress(DatabaseInitializationProgress("Preparing the database folder...", 2, 7))
            // Create parent directories if they don't exist
            location.path.parent?.let { parentDir ->
                if (!parentDir.exists()) {
                    Files.createDirectories(parentDir)
                }
            }

            onProgress(DatabaseInitializationProgress("Opening the SQLite database...", 3, 7))
            // Create driver with JDBC URL and properties to enable foreign keys
            // Note: foreign_keys must be set via Properties for file-based databases
            // See: https://github.com/sqldelight/sqldelight/issues/2421
            val properties =
                Properties().apply {
                    put("foreign_keys", "true")
                }
            val driver = JdbcSqliteDriver(location.jdbcUrl, properties)

            onProgress(DatabaseInitializationProgress("Applying database settings...", 4, 7))
            // Apply cross-platform pragmas (foreign_keys already set via Properties above).
            // WAL mode and busy_timeout are JVM-only: on Android WAL is enabled via
            // enableWriteAheadLogging() and the connection pool handles contention differently.
            DatabaseConfig.connectionPragmas
                .filterNot { it.contains("foreign_keys", ignoreCase = true) }
                .plus(listOf("PRAGMA journal_mode = WAL", "PRAGMA busy_timeout = 5000"))
                .forEach { pragma ->
                    driver.execute(null, pragma, 0)
                }

            if (isNewDatabase) {
                onProgress(DatabaseInitializationProgress("Creating the database schema...", 5, 7))
                // Create schema for new database
                MoneyManagerDatabase.Schema.create(driver)
            } else {
                onProgress(DatabaseInitializationProgress("Checking the database schema...", 5, 7))
            }

            val database = MoneyManagerDatabaseWrapper(driver)

            if (isNewDatabase) {
                onProgress(DatabaseInitializationProgress("Adding default currencies and settings...", 6, 7))
                DatabaseConfig.seedDatabase(database)
            } else {
                onProgress(DatabaseInitializationProgress("Preparing repositories...", 6, 7))
            }

            onProgress(DatabaseInitializationProgress("Finishing database startup...", 7, 7))
            database
        }

    override suspend fun databaseExists(location: DbLocation): Boolean =
        withContext(Dispatchers.IO) {
            location.exists()
        }

    override fun getDefaultLocation() = DEFAULT_DATABASE_PATH

    override suspend fun backupDatabase(location: DbLocation): DbLocation =
        withContext(Dispatchers.IO) {
            require(location.exists()) { "Database does not exist at: $location" }

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

    override suspend fun snapshot(database: MoneyManagerDatabaseWrapper): ByteArray =
        withContext(Dispatchers.IO) {
            // VACUUM INTO requires the target file to not already exist.
            val tempFile = Files.createTempFile("mm-snapshot", ".db")
            Files.delete(tempFile)
            try {
                database.executeWithParams("VACUUM INTO ?", 1, listOf(tempFile.toString()))
                Files.readAllBytes(tempFile)
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }

    override suspend fun restore(location: DbLocation, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            location.path.parent?.let { parentDir ->
                if (!parentDir.exists()) {
                    Files.createDirectories(parentDir)
                }
            }
            // Drop stale WAL/SHM sidecars so the restored file isn't shadowed by an old write-ahead log.
            Files.deleteIfExists(Paths.get("${location.path}-wal"))
            Files.deleteIfExists(Paths.get("${location.path}-shm"))
            Files.write(location.path, bytes)
        }
}
