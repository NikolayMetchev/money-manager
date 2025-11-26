package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * File-based database driver factory for JVM platform.
 * Persists data to a SQLite database file.
 *
 * @param databasePath Optional path to the database file. If null, uses the default path.
 */
class FileDatabaseDriverFactory(private val databasePath: Path? = null) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        // Use provided path or default path
        val dbFile = databasePath ?: getDefaultDatabasePath()

        // Auto-detect if this is a new database
        val isNewDatabase = !dbFile.exists()

        if (isNewDatabase) {
            dbFile.parent?.let { parentDir ->
                if (!parentDir.exists()) {
                    Files.createDirectories(parentDir)
                }
            }
        }

        // Create driver with JDBC URL
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$dbFile")

        if (isNewDatabase) {
            // Create schema for new database
            MoneyManagerDatabase.Schema.create(driver)
        }
        // For existing databases, schema already exists
        // Future: Handle schema migrations here

        return driver
    }
}

fun getDefaultDatabasePath(): Path {
    val userHome = System.getProperty("user.home")
    return Paths.get(userHome, ".moneymanager", "default.db")
}
