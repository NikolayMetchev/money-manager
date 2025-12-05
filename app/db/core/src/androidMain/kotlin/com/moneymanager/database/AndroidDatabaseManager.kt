package com.moneymanager.database

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.moneymanager.database.sql.MoneyManagerDatabase
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
            // Track if this is a new database for seeding
            var isNewDatabase = false

            // AndroidSqliteDriver automatically handles schema creation
            val driver =
                AndroidSqliteDriver(
                    schema = MoneyManagerDatabase.Schema,
                    context = context,
                    name = location.name,
                    callback =
                        object : AndroidSqliteDriver.Callback(MoneyManagerDatabase.Schema) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                super.onCreate(db)
                                isNewDatabase = true
                            }

                            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                super.onOpen(db)
                                // Apply connection-level PRAGMA settings
                                DatabaseConfig.connectionPragmas.forEach { pragma ->
                                    db.execSQL(pragma)
                                }
                            }
                        },
                )

            val database = MoneyManagerDatabase(driver)

            // Seed data after database is created
            if (isNewDatabase) {
                DatabaseConfig.seedDatabase(database)
            }

            database
        }

    override suspend fun databaseExists(location: DbLocation): Boolean {
        // On Android, we can check if the database file exists in the databases directory
        return context.getDatabasePath(location.name).exists()
    }

    override fun getDefaultLocation() = DEFAULT_DB_LOCATION
}
