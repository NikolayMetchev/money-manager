package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties

actual fun createTestDatabaseLocation(): DbLocation {
    val tempDir = Files.createTempDirectory("moneymanager-test")
    val dbPath = Paths.get(tempDir.toString(), "test-${System.currentTimeMillis()}.db")
    return DbLocation(dbPath)
}

actual fun deleteTestDatabase(location: DbLocation) {
    try {
        Files.deleteIfExists(location.path)
        Files.deleteIfExists(location.path.parent)
    } catch (e: Exception) {
        // Ignore cleanup errors
    }
}

/**
 * JVM implementation for creating a test SqlDriver.
 * Uses JDBC SQLite driver with the same configuration as JvmDatabaseManager.
 */
actual fun createTestDriver(location: DbLocation): SqlDriver {
    val jdbcUrl = "jdbc:sqlite:${location.path}"
    val properties =
        Properties().apply {
            put("foreign_keys", "true")
        }
    return JdbcSqliteDriver(jdbcUrl, properties)
}
