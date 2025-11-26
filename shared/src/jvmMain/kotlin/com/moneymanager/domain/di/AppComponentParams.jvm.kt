package com.moneymanager.domain.di

/**
 * JVM-specific parameters for AppComponent creation.
 *
 * @property databasePath Optional path to the database file.
 *                        If null, DatabaseDriverFactory will use the default location
 *                        (~/.moneymanager/default.db)
 */
actual class AppComponentParams(
    val databasePath: String? = null,
)
