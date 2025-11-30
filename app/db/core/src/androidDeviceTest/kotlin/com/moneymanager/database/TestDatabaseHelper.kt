package com.moneymanager.database

import androidx.test.platform.app.InstrumentationRegistry

fun createTestDatabaseLocation(): DbLocation {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dbName = "test-${System.currentTimeMillis()}.db"
    // Clean up first if it exists
    context.deleteDatabase(dbName)
    return DbLocation(dbName)
}

fun deleteTestDatabase(location: DbLocation) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.deleteDatabase(location.name)
}
