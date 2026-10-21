package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.currency.IsoCurrency
import kotlinx.io.files.Path
import java.util.Properties

/** The desktop [FileDatabaseManager]: SQLite over sqlite-jdbc, with cached prepared statements. */
class JvmDatabaseManager(
    currenciesToSeed: () -> List<IsoCurrency> = { DatabaseConfig.allCurrencies },
) : FileDatabaseManager(currenciesToSeed) {
    override fun openDriver(file: Path): SqlDriver {
        // foreign_keys must be set via Properties for file-based databases, since the driver opens a
        // connection per thread: https://github.com/sqldelight/sqldelight/issues/2421
        val properties = Properties().apply { put("foreign_keys", "true") }
        val driver = CachingJdbcSqliteDriver("jdbc:sqlite:$file", properties)
        listOf("PRAGMA journal_mode = WAL", "PRAGMA busy_timeout = ${DatabaseConfig.BUSY_TIMEOUT_MS}").forEach { pragma ->
            driver.execute(null, pragma, 0)
        }
        return driver
    }
}
