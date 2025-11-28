package com.moneymanager.database

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        // On Android, we can check if the database file exists in the databases directory
        val dbFile = context.getDatabasePath(location.name)
        return dbFile.exists()
    }

    override fun getDefaultLocation(): DbLocation {
        return DbLocation(DEFAULT_DATABASE_NAME)
    }
}
