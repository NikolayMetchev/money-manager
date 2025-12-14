package com.moneymanager.test.database

import com.moneymanager.database.DatabaseManager

/**
 * Creates a platform-specific DatabaseManager for testing.
 */
expect fun createTestDatabaseManager(): DatabaseManager
