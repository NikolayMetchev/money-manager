package com.moneymanager.database

import androidx.test.platform.app.InstrumentationRegistry

actual fun createTestDatabaseManager(): DatabaseManager {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return AndroidDatabaseManager(context)
}
