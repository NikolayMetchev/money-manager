package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * In-memory database driver factory for JVM platform.
 * Useful for tests and temporary sessions.
 */
class InMemoryDatabaseDriverFactory : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyManagerDatabase.Schema.create(driver)
        return driver
    }
}

/**
 * File-based database driver factory for JVM platform.
 * Persists data to a SQLite database file.
 *
 * @param databasePath Optional path to the database file. If null, uses the default path.
 */
class FileDatabaseDriverFactory(private val databasePath: String? = null) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        // Use provided path or default path
        val resolvedPath = resolveDatabasePath(databasePath ?: getDefaultDatabasePath())
        val dbFile = File(resolvedPath)

        // Auto-detect if this is a new database
        val isNewDatabase = !dbFile.exists()

        // Ensure parent directory exists
        dbFile.parentFile?.let { parentDir ->
            if (!parentDir.exists()) {
                Files.createDirectories(parentDir.toPath())
            }
        }

        // Create driver with JDBC URL
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$resolvedPath")

        if (isNewDatabase) {
            // Create schema for new database
            MoneyManagerDatabase.Schema.create(driver)
        }
        // For existing databases, schema already exists
        // Future: Handle schema migrations here

        return driver
    }

    private fun resolveDatabasePath(path: String): String {
        // If path starts with ~, expand to user home directory
        return if (path.startsWith("~")) {
            val userHome = System.getProperty("user.home")
            path.replaceFirst("~", userHome)
        } else {
            path
        }
    }

    companion object {
        /**
         * Default database location for JVM platform.
         * Located at ~/.moneymanager/default.db
         */
        fun getDefaultDatabasePath(): String {
            val userHome = System.getProperty("user.home")
            return Paths.get(userHome, ".moneymanager", "default.db").toString()
        }
    }
}
