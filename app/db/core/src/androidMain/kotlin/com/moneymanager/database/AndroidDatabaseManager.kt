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
            // Check if this is a new database before opening
            val isNewDatabase = !context.getDatabasePath(location.name).exists()

            // Use custom callback that handles existing databases gracefully
            val driver =
                AndroidSqliteDriver(
                    schema = MoneyManagerDatabase.Schema,
                    context = context,
                    name = location.name,
                    callback =
                        object : AndroidSqliteDriver.Callback(MoneyManagerDatabase.Schema) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                // Only create schema for truly new databases
                                if (isNewDatabase) {
                                    super.onCreate(db)
                                }
                                // For existing databases (e.g., copied test DBs), skip schema creation
                                // This allows opening databases with potentially incompatible schemas
                                // which will be detected at runtime via schema error handling
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) {
                                // Skip automatic migration - let schema errors surface at runtime
                                // The global schema error handler will show DatabaseSchemaErrorDialog
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

            if (isNewDatabase) {
                DatabaseConfig.seedDatabase(database, driver)
            }

            database
        }

    override suspend fun databaseExists(location: DbLocation): Boolean {
        // On Android, we can check if the database file exists in the databases directory
        return context.getDatabasePath(location.name).exists()
    }

    override fun getDefaultLocation() = DEFAULT_DB_LOCATION

    override suspend fun backupDatabase(location: DbLocation): DbLocation =
        withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(location.name)
            if (!dbFile.exists()) {
                throw IllegalArgumentException("Database does not exist: ${location.name}")
            }

            val backupName = "${location.name}.backup"
            val backupFile = context.getDatabasePath(backupName)
            val backupLocation = DbLocation(backupName)

            // Delete existing backup if it exists
            if (backupFile.exists()) {
                backupFile.delete()
            }

            // Copy the database file to backup
            dbFile.copyTo(backupFile, overwrite = true)

            // Delete the original database so a fresh one can be created
            context.deleteDatabase(location.name)

            backupLocation
        }

    override suspend fun deleteDatabase(location: DbLocation): Unit =
        withContext(Dispatchers.IO) {
            // Use Android's deleteDatabase method for proper cleanup
            context.deleteDatabase(location.name)
        }
}
