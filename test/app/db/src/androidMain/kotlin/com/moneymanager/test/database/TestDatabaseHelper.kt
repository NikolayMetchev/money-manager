package com.moneymanager.test.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.moneymanager.database.DbLocation
import com.moneymanager.di.AppComponentParams

actual fun createTestDatabaseLocation(): DbLocation {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dbName = "test-${System.currentTimeMillis()}.db"
    // Clean up first if it exists
    context.deleteDatabase(dbName)
    return DbLocation(dbName)
}

actual fun deleteTestDatabase(location: DbLocation) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.deleteDatabase(location.name)
}

actual fun createTestAppComponentParams(): AppComponentParams = AppComponentParams(ApplicationProvider.getApplicationContext())
