package com.moneymanager.test.database

import com.moneymanager.database.DatabaseManager

/** Creates a platform-specific DatabaseManager for testing, whose fresh databases hold [testCurrencies]. */
expect fun createTestDatabaseManager(): DatabaseManager
