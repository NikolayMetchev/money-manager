package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.kermit.Logger
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.NO_VERSION_CHECK
import com.moneymanager.domain.model.CurrencyExportEntry
import kotlinx.coroutines.IO
import kotlinx.io.files.Path
import co.touchlab.sqliter.interop.Logger as SqliterLogger

/**
 * The native (CLI) [FileDatabaseManager]: SQLDelight's native driver over the SQLite bundled by
 * :utils:sqlite3.
 *
 * [NO_VERSION_CHECK] turns off SQLiter's own create/upgrade, which keys off `user_version` and would take
 * every desktop-created database (version 0) for an empty one. [FileDatabaseManager] creates the schema
 * from the file's existence instead, the same rule the desktop app uses, and `user_version` is never
 * written.
 *
 * SQLiter's default logger prints every failed statement to stdout, which would corrupt the CLI's JSON
 * output; [KermitSqliterLogger] sends it to Kermit instead.
 */
class NativeDatabaseManager(
    currenciesToSeed: () -> List<CurrencyExportEntry> = { emptyList() },
) : FileDatabaseManager(currenciesToSeed) {
    override fun openDriver(file: Path): SqlDriver =
        NativeSqliteDriver(
            DatabaseConfiguration(
                name = file.name,
                version = NO_VERSION_CHECK,
                create = {},
                journalMode = JournalMode.WAL,
                loggingConfig = DatabaseConfiguration.Logging(logger = KermitSqliterLogger),
                extendedConfig =
                    DatabaseConfiguration.Extended(
                        foreignKeyConstraints = true,
                        busyTimeout = DatabaseConfig.BUSY_TIMEOUT_MS,
                        basePath = checkNotNull(file.parent) { "No parent directory: $file" }.toString(),
                    ),
            ),
        )
}

private val logger = Logger.withTag("NativeDatabaseManager")

/** SQLiter's errors at debug level: the statement's exception still reaches the caller, which reports it. */
private object KermitSqliterLogger : SqliterLogger {
    override val eActive: Boolean = true
    override val vActive: Boolean = false

    override fun trace(message: String) = logger.v { message }

    override fun vWrite(message: String) = logger.v { message }

    override fun eWrite(
        message: String,
        exception: Throwable?,
    ) = logger.d(exception) { message }
}
