package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.Companion.IN_MEMORY

/**
 * In-memory database driver factory for JVM platform.
 * Useful for tests and temporary sessions.
 */
class InMemoryDatabaseDriverFactory : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        val driver = JdbcSqliteDriver(IN_MEMORY)
        MoneyManagerDatabase.Schema.create(driver)
        return driver
    }
}
