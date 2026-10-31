package com.moneymanager.test.database

import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.WebDatabaseManager

actual fun createTestDatabaseManager(): DatabaseManager = WebDatabaseManager(MemorySqliteChannel, currenciesToSeed = { testCurrencies })
