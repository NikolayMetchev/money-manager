@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

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
     * Default currencies to seed when creating a new database.
     * Each entry is a pair of (ISO 4217 code, human-readable name).
     */
    val defaultCurrencies =
        listOf(
            "USD" to "US Dollar",
            "GBP" to "British Pound",
            "EUR" to "Euro",
            "JPY" to "Japanese Yen",
        )

    /**
     * Seeds the database with default data.
     * Should be called once after creating a new database.
     */
    suspend fun seedDatabase(database: MoneyManagerDatabase) {
        val currencyRepository = RepositorySet(database).currencyRepository
        defaultCurrencies.forEach { (code, name) ->
            currencyRepository.upsertCurrencyByCode(code, name)
        }
    }
}
