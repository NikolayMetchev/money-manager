package com.moneymanager.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.moneymanager.database.sql.MoneyManagerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.io.path.exists

private val DbLocation.jdbcUrl: String
    get() =
        if (isInMemory()) {
            "jdbc:sqlite::memory:"
        } else {
            "jdbc:sqlite:${path ?: error("Non-memory database must have a path")}"
        }

/**
 * JVM implementation of DatabaseManager.
 * Manages SQLite database files on the file system.
 * Stateless - does not track open databases.
 */
class JvmDatabaseManager : DatabaseManager {
    override suspend fun openDatabase(location: DbLocation): MoneyManagerDatabase =
        withContext(Dispatchers.IO) {
            // Auto-detect if this is a new database
            val isNewDatabase = location.isInMemory() || !location.exists()

            if (isNewDatabase && location.path != null) {
                // Create parent directories if they don't exist
                location.path.parent?.let { parentDir ->
                    if (!parentDir.exists()) {
                        Files.createDirectories(parentDir)
                    }
                }
            }

            // Create driver with JDBC URL
            val driver = JdbcSqliteDriver(location.jdbcUrl)

            // Apply connection-level PRAGMA settings
            DatabaseConfig.connectionPragmas.forEach { pragma ->
                driver.execute(null, pragma, 0)
            }

            if (isNewDatabase) {
                // Create schema for new database
                MoneyManagerDatabase.Schema.create(driver)
            }

            MoneyManagerDatabase(driver)
        }

    override suspend fun databaseExists(location: DbLocation): Boolean =
        withContext(Dispatchers.IO) {
            location.exists()
        }

    override fun getDefaultLocation() = DEFAULT_DATABASE_PATH
}
