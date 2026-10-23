package com.moneymanager.test.database

import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.WebDatabaseManager

actual fun createTestDatabaseManager(seedAllCurrencies: Boolean): DatabaseManager =
    if (seedAllCurrencies) {
        WebDatabaseManager(MemorySqliteChannel)
    } else {
        WebDatabaseManager(MemorySqliteChannel, currenciesToSeed = { minimalTestCurrencies })
    }
