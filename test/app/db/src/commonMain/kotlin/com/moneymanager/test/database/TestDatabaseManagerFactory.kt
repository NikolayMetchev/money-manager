package com.moneymanager.test.database

import com.moneymanager.database.DatabaseManager

/**
 * Creates a platform-specific DatabaseManager for testing.
 *
 * @param seedAllCurrencies seed the full platform currency list into fresh databases instead of
 * [minimalTestCurrencies]; only needed by tests that assert against the complete list.
 */
expect fun createTestDatabaseManager(seedAllCurrencies: Boolean = false): DatabaseManager
