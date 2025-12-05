@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.currency.Currency
import com.moneymanager.database.sql.MoneyManagerDatabase

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
     * All available ISO 4217 currencies from the platform.
     */
    val allCurrencies: List<Currency>
        get() = Currency.getAllCurrencies()

    /**
     * Seeds the database with all available currencies.
     * Should be called once after creating a new database.
     */
    suspend fun seedDatabase(database: MoneyManagerDatabase) {
        val currencyRepository = RepositorySet(database).currencyRepository
        allCurrencies.forEach { currency ->
            currencyRepository.upsertCurrencyByCode(currency.code, currency.displayName)
        }
    }
}
