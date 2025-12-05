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
     * These are ISO 4217 currency codes: USD, GBP, EUR, JPY.
     */
    val defaultCurrencies =
        listOf(
            "USD",
            "GBP",
            "EUR",
            "JPY",
        )

    /**
     * Seeds the database with default data.
     * Should be called once after creating a new database.
     */
    suspend fun seedDatabase(database: MoneyManagerDatabase) {
        val assetRepository = RepositorySet(database).assetRepository
        defaultCurrencies.forEach { currency ->
            assetRepository.upsertAssetByName(currency)
        }
    }
}
