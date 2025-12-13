package com.moneymanager.database

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.moneymanager.database.sql.MoneyManagerDatabase

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

/**
 * Android implementation for creating a test SqlDriver.
 * Uses AndroidSqliteDriver with the same configuration as AndroidDatabaseManager.
 */
actual fun createTestDriver(location: DbLocation): SqlDriver {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return AndroidSqliteDriver(
        schema = MoneyManagerDatabase.Schema,
        context = context,
        name = location.name,
    )
}
