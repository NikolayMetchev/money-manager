package com.moneymanager.test.database

import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.JvmDatabaseManager

actual fun createTestDatabaseManager(seedAllCurrencies: Boolean): DatabaseManager =
    if (seedAllCurrencies) {
        JvmDatabaseManager()
    } else {
        JvmDatabaseManager(currenciesToSeed = { minimalTestCurrencies })
    }
