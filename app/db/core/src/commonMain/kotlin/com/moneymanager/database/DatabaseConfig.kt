@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.currency.Currency
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
    const val ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID: Long = WellKnownIds.ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID
    const val BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID: Long = WellKnownIds.BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID
    const val ACCOUNT_SORT_CODE_ATTR_TYPE_ID: Long = WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID
    const val ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID: Long = WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID
    const val RECONCILED_RELATIONSHIP_TYPE_ID: Long = WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID
    const val FEE_RELATIONSHIP_TYPE_ID: Long = WellKnownIds.FEE_RELATIONSHIP_TYPE_ID

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
}
