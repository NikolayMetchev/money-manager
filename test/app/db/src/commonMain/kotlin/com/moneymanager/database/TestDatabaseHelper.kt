package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver

expect fun createTestDatabaseLocation(): DbLocation

expect fun deleteTestDatabase(location: DbLocation)

/**
 * Platform-specific helper to create a SqlDriver for testing.
 * This allows tests to access the driver directly for metadata queries.
 */
expect fun createTestDriver(location: DbLocation): SqlDriver
