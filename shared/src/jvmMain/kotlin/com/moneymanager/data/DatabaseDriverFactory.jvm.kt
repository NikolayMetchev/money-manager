package com.moneymanager.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.moneymanager.database.MoneyManagerDatabase

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyManagerDatabase.Schema.create(driver)
        return driver
    }

    fun createDriver(databasePath: String): SqlDriver {
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$databasePath")
        MoneyManagerDatabase.Schema.create(driver)
        return driver
    }
}
