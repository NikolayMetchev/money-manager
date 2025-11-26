package com.moneymanager.database.test

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.DatabaseDriverFactory

/**
 * Utility for creating in-memory database drivers for testing.
 *
 * This provides a simple API for tests to create isolated database instances
 * without needing to manage file paths or cleanup.
 *
 * Usage in tests:
 * ```
 * @BeforeTest
 * fun setup() {
 *     driver = TestDatabaseDriverFactory.create()
 *     database = MoneyManagerDatabase(driver)
 * }
 * ```
 */
object TestDatabaseDriverFactory {
    /**
     * Creates a new in-memory SqlDriver for testing.
     *
     * Each call creates a fresh database instance with the schema already created.
     * The database exists only in memory and will be garbage collected when the driver is closed.
     *
     * @return A SqlDriver instance backed by an in-memory database
     */
    fun create(): SqlDriver {
        return DatabaseDriverFactory().createDriver()
    }
}
