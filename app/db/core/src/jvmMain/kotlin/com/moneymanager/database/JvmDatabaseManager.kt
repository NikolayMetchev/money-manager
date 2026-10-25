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
        // Per-connection pragmas go in Properties, since the driver opens several connections; a PRAGMA
        // statement would reach only one of them: https://github.com/sqldelight/sqldelight/issues/2421
        val properties =
            Properties().apply {
                put("foreign_keys", "true")
                put("busy_timeout", DatabaseConfig.BUSY_TIMEOUT_MS.toString())
            }
        val driver = CachingJdbcSqliteDriver("jdbc:sqlite:$file", properties)
        // journal_mode is stored in the file, so one connection setting it covers them all.
        driver.execute(null, "PRAGMA journal_mode = WAL", 0)
        return driver
    }
}
