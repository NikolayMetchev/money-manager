package com.moneymanager.database

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.currency.IsoCurrencies
import com.moneymanager.currency.IsoCurrency
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
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
     * The ISO 4217 currencies a new database is seeded with: the table bundled into the app, the same on
     * every platform. The strategy catalog publishes a newer copy that installs any codes added since.
     */
    val allCurrencies: List<IsoCurrency>
        get() = IsoCurrencies.all

    // GBP is seeded with a fixed id in :app:db:seed (StaticSeed.sq) so the build-time-generated QIF
    // strategies can reference it as a constant; runtime seeding below skips it to avoid a duplicate.
    private const val GBP_CODE = "GBP"

    /**
     * Seeds [currencies] (every code except [GBP_CODE]) for a freshly created database, recording
     * SYSTEM provenance for each — the provenance that marks a currency as part of the catalog's
     * currency list (see `StrategyLibraryService`). Tests pass a minimal list instead: seeding ~230 currencies
     * (with audit triggers) per test dominated DB-test setup time. Runs after Schema.create (which
     * already seeded the lookups, the system device, and GBP).
     */
    suspend fun seedCurrencies(
        database: MoneyManagerDatabaseWrapper,
        currencies: List<IsoCurrency> = allCurrencies,
    ) {
        with(database) {
            currencies.forEach { currency ->
                if (currency.code == GBP_CODE) return@forEach
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
                currencyWriteQueries.insert(currencyId, currency.code, currency.name, scaleFactor)
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
