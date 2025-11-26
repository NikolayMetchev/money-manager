package com.moneymanager.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(databasePath: String?): SqlDriver {
        // On Android, we always use the system-managed database location
        // The databasePath parameter is ignored to maintain platform consistency
        return AndroidSqliteDriver(
            schema = MoneyManagerDatabase.Schema,
            context = context,
            name = "money_manager.db",
        )
    }
}
