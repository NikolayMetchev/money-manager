package com.moneymanager.database

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.CurrencyExportEntry
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds

/**
 * Centralized database configuration for SQLite PRAGMA settings and seed data.
 * These settings are applied per-connection (not persisted to the database file).
 */
object DatabaseConfig {
    /**
     * SQL statements to execute when opening a database connection.
     * Applied to all database connections (JVM, Android, etc.)
     */
    val connectionPragmas =
        listOf(
            // Enable foreign key constraints (disabled by default in SQLite)
            "PRAGMA foreign_keys = ON",
        )

    /**
     * How long a file-backed connection waits for another connection's lock before failing with
     * SQLITE_BUSY. Android's connection pool handles contention itself.
     */
    const val BUSY_TIMEOUT_MS = 5000

    /**
     * Seeds [currencies] for a freshly created database, recording
     * SYSTEM provenance for each — the provenance that marks a currency as part of the catalog's
     * currency list (see `StrategyLibraryService`). The app seeds none (a person installs the list from the
     * catalog); tests seed the few they use. Runs after Schema.create (which already seeded the lookups and the
     * system device).
     */
    suspend fun seedCurrencies(
        database: MoneyManagerDatabaseWrapper,
        currencies: List<CurrencyExportEntry>,
    ) {
        with(database) {
            currencies.forEach { currency ->
                val scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR
                // Allocate an id from the shared `asset` id space first (asset has no triggers, so
                // last_insert_rowid() is reliable), then insert the currency with that id.
                // insert + last_insert_rowid() must share one connection (the driver may pool
                // connections), so allocate the asset id inside a transaction.
                val currencyId =
                    assetWriteQueries.transactionWithResult {
                        assetWriteQueries.insert()
                        assetWriteQueries.lastInsertedId().awaitAsOne()
                    }
                currencyWriteQueries.insert(currencyId, currency.code, currency.name, scaleFactor, currency.fractionDigits?.toLong())
                recordSource(
                    DeviceId(WellKnownIds.SYSTEM_DEVICE_ID),
                    EntityType.CURRENCY,
                    currencyId,
                    1L,
                    Source.System,
                )
            }
        }
    }
}
