package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.Companion.IN_MEMORY
import java.nio.file.Files
import kotlin.io.path.exists

object JvmSqlDriverFactory : SqlDriverFactory {
    override fun createSqlDriver(dbLocation: DbLocation): SqlDriver {
        // Use provided path or default path
        val dbFile = dbLocation.path

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

    override fun createInMemorySqlDriver(): SqlDriver {
        val driver = JdbcSqliteDriver(IN_MEMORY)
        MoneyManagerDatabase.Schema.create(driver)
        return driver
    }
}
