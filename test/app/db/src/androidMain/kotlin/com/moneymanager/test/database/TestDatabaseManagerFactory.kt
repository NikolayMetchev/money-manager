package com.moneymanager.test.database

import androidx.test.platform.app.InstrumentationRegistry
import com.moneymanager.database.AndroidDatabaseManager
import com.moneymanager.database.DatabaseManager

actual fun createTestDatabaseManager(seedAllCurrencies: Boolean): DatabaseManager {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return if (seedAllCurrencies) {
        AndroidDatabaseManager(context)
    } else {
        AndroidDatabaseManager(context, currenciesToSeed = { minimalTestCurrencies })
    }
}
