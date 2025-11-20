package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.moneymanager.database.MoneyManagerDatabase

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyManagerDatabase.Schema.create(driver)
        return driver
    }

    fun createDriver(databasePath: String, isNewDatabase: Boolean = false): SqlDriver {
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$databasePath")

        if (isNewDatabase) {
            // Create schema for new database
            MoneyManagerDatabase.Schema.create(driver)
        }
        // For existing databases, schema already exists
        // Future: Handle schema migrations here

        return driver
    }
}
