@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.currency.Currency
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
    // Canonical values live in WellKnownIds (db-free, so the import modules can reference them); these
    // aliases keep existing db-layer call sites and the seeding below working. The database seeds
    // exactly these ids.
    const val EXCLUDED_ATTR_TYPE_ID: Long = WellKnownIds.EXCLUDED_ATTR_TYPE_ID
    const val RECONCILED_RELATIONSHIP_TYPE_ID: Long = WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID
    const val FEE_RELATIONSHIP_TYPE_ID: Long = WellKnownIds.FEE_RELATIONSHIP_TYPE_ID
    const val PASS_THROUGH_RELATIONSHIP_TYPE_ID: Long = WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID

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
     * All available ISO 4217 currencies from the platform.
     */
    val allCurrencies: List<Currency>
        get() = Currency.getAllCurrencies()

    // GBP is seeded with a fixed id in :app:db:seed (StaticSeed.sq) so the build-time-generated QIF
    // strategies can reference it as a constant; runtime seeding below skips it to avoid a duplicate.
    private const val GBP_CODE = "GBP"

    /**
     * Seeds [currencies] (every code except [GBP_CODE]) for a freshly created database, recording
     * SYSTEM provenance for each. Defaults to the runtime platform currency list (java.util.Currency)
     * so the seeded set matches the device — Android exposes more ISO 4217 codes than the JVM, so this
     * cannot be frozen at build time. Tests pass a minimal list instead: seeding ~230 currencies
     * (with audit triggers) per test dominated DB-test setup time. Runs after Schema.create (which
     * already seeded the lookups, the system device, and GBP).
     */
    fun seedCurrencies(
        database: MoneyManagerDatabaseWrapper,
        currencies: List<Currency> = allCurrencies,
    ) {
        with(database) {
            currencies.forEach { currency ->
                if (currency.code == GBP_CODE) return@forEach
                val scaleFactor = CurrencyScaleFactors.getScaleFactor(currency.code)
                // Allocate an id from the shared `asset` id space first (asset has no triggers, so
                // last_insert_rowid() is reliable), then insert the currency with that id.
                // insert + last_insert_rowid() must share one connection (the driver may pool
                // connections), so allocate the asset id inside a transaction.
                val currencyId =
                    assetWriteQueries.transactionWithResult {
                        assetWriteQueries.insert()
                        assetWriteQueries.lastInsertedId().executeAsOne()
                    }
                currencyWriteQueries.insert(currencyId, currency.code, currency.displayName, scaleFactor.toLong())
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
