package com.moneymanager.test.database

import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.NativeDatabaseManager

actual fun createTestDatabaseManager(seedAllCurrencies: Boolean): DatabaseManager =
    if (seedAllCurrencies) {
        NativeDatabaseManager()
    } else {
        NativeDatabaseManager(currenciesToSeed = { minimalTestCurrencies })
    }
