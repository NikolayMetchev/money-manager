package com.moneymanager.test.database

import androidx.test.platform.app.InstrumentationRegistry
import com.moneymanager.database.AndroidDatabaseManager
import com.moneymanager.database.DatabaseManager

actual fun createTestDatabaseManager(): DatabaseManager {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return AndroidDatabaseManager(context)
}
