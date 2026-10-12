package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.NO_VERSION_CHECK
import com.moneymanager.currency.IsoCurrency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.files.Path

/**
 * The native (CLI) [FileDatabaseManager]: SQLDelight's native driver over the SQLite bundled by
 * :utils:sqlite3.
 *
 * [NO_VERSION_CHECK] turns off SQLiter's own create/upgrade, which keys off `user_version` and would take
 * every desktop-created database (version 0) for an empty one. [FileDatabaseManager] creates the schema
 * from the file's existence instead, the same rule the desktop app uses, and `user_version` is never
 * written.
 */
class NativeDatabaseManager(
    currenciesToSeed: () -> List<IsoCurrency> = { DatabaseConfig.allCurrencies },
) : FileDatabaseManager(Dispatchers.IO, currenciesToSeed) {
    override fun openDriver(file: Path): SqlDriver =
        NativeSqliteDriver(
            DatabaseConfiguration(
                name = file.name,
                version = NO_VERSION_CHECK,
                create = {},
                journalMode = JournalMode.WAL,
                extendedConfig =
                    DatabaseConfiguration.Extended(
                        foreignKeyConstraints = true,
                        busyTimeout = DatabaseConfig.BUSY_TIMEOUT_MS,
                        basePath = checkNotNull(file.parent) { "No parent directory: $file" }.toString(),
                    ),
            ),
        )
}
