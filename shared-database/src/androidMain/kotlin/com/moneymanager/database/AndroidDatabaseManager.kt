package com.moneymanager.database

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of DatabaseManager.
 * Manages SQLite databases using Android's built-in database support.
 */
class AndroidDatabaseManager(private val context: Context) : DatabaseManager {
    private var currentDatabase: MoneyManagerDatabase? = null

    override suspend fun createDatabase(location: DbLocation): MoneyManagerDatabase =
        withContext(Dispatchers.IO) {
            // Close any existing database first
            closeDatabase()

            // Create driver with Android SQLite
            // AndroidSqliteDriver automatically handles schema creation
            val driver =
                AndroidSqliteDriver(
                    schema = MoneyManagerDatabase.Schema,
                    context = context,
                    name = location.name,
                )

            // Create and cache the database instance
            val database = MoneyManagerDatabase(driver)
            currentDatabase = database
            database
        }

    override suspend fun openDatabase(location: DbLocation): MoneyManagerDatabase {
        // openDatabase and createDatabase have the same logic on Android
        // AndroidSqliteDriver handles both creating new and opening existing databases
        return createDatabase(location)
    }

    override suspend fun closeDatabase() {
        currentDatabase?.let { db ->
            // SQLDelight doesn't provide a direct close method on the database
            // The driver should be closed, but we don't have direct access to it here
            // For now, just clear the reference
            currentDatabase = null
        }
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
