package com.moneymanager.database

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DEFAULT_DB_LOCATION = DbLocation(DEFAULT_DATABASE_NAME)

/**
 * Android implementation of DatabaseManager.
 * Manages SQLite databases using Android's built-in database support.
 * Stateless - does not track open databases.
 */
class AndroidDatabaseManager(private val context: Context) : DatabaseManager {
    override suspend fun openDatabase(location: DbLocation): MoneyManagerDatabase =
        withContext(Dispatchers.IO) {
            // AndroidSqliteDriver automatically handles schema creation
            val driver =
                AndroidSqliteDriver(
                    schema = MoneyManagerDatabase.Schema,
                    context = context,
                    name = location.name,
                )

            MoneyManagerDatabase(driver)
        }

    override suspend fun databaseExists(location: DbLocation): Boolean {
        // In-memory databases always "exist"
        if (location.isInMemory()) return true
        // On Android, we can check if the database file exists in the databases directory
        return context.getDatabasePath(location.name).exists()
    }

    override fun getDefaultLocation() = DEFAULT_DB_LOCATION
}
