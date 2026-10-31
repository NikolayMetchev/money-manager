package com.moneymanager.test.database

import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.NativeDatabaseManager

actual fun createTestDatabaseManager(): DatabaseManager = NativeDatabaseManager(currenciesToSeed = { testCurrencies })
