package com.moneymanager.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.moneymanager.database.sql.seed.MoneyManagerDatabase
import com.moneymanager.domain.model.DEFAULT_DATABASE_NAME
import com.moneymanager.domain.model.DbLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val DEFAULT_DB_LOCATION = DbLocation(DEFAULT_DATABASE_NAME)

/**
 * Android implementation of DatabaseManager.
 * Manages SQLite databases using Android's built-in database support.
 * Stateless - does not track open databases.
 */
class AndroidDatabaseManager(
    private val context: Context,
) : DatabaseManager {
    override suspend fun openDatabase(location: DbLocation): MoneyManagerDatabaseWrapper = openDatabaseWithProgress(location) {}

    override suspend fun openDatabaseWithProgress(
        location: DbLocation,
        onProgress: (DatabaseInitializationProgress) -> Unit,
    ): MoneyManagerDatabaseWrapper =
        withContext(Dispatchers.IO) {
            onProgress(DatabaseInitializationProgress("Checking for an existing database...", 1, 6))
            // Check if this is a new database before opening
            val isNewDatabase = !context.getDatabasePath(location.name).exists()

            onProgress(DatabaseInitializationProgress("Opening the SQLite database...", 2, 6))
            // Use custom callback that handles existing databases gracefully
            val driver =
                AndroidSqliteDriver(
                    schema = MoneyManagerDatabase.Schema,
                    context = context,
                    name = location.name,
                    callback =
                        object : AndroidSqliteDriver.Callback(MoneyManagerDatabase.Schema) {
                            override fun onConfigure(db: SupportSQLiteDatabase) {
                                super.onConfigure(db)
                                // WAL must be enabled in onConfigure so it is active during
                                // onCreate and onUpgrade, not just after onOpen.
                                db.enableWriteAheadLogging()
                                DatabaseConfig.connectionPragmas.forEach { pragma ->
                                    db.execSQL(pragma)
                                }
                            }

                            override fun onCreate(db: SupportSQLiteDatabase) {
                                // Only create schema for truly new databases
                                if (isNewDatabase) {
                                    super.onCreate(db)
                                }
                                // For existing databases (e.g., copied test DBs), skip schema creation
                                // This allows opening databases with potentially incompatible schemas
                                // which will be detected at runtime via schema error handling
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) {
                                // Skip automatic migration - let schema errors surface at runtime
                                // The global schema error handler will show DatabaseSchemaErrorDialog
                            }
                        },
                )

            onProgress(
                if (isNewDatabase) {
                    DatabaseInitializationProgress("Creating the database schema...", 3, 6)
                } else {
                    DatabaseInitializationProgress("Checking the database schema...", 3, 6)
                },
            )
            val database = MoneyManagerDatabaseWrapper(driver)

            onProgress(DatabaseInitializationProgress("Applying database settings...", 4, 6))
            if (isNewDatabase) {
                onProgress(DatabaseInitializationProgress("Adding default currencies and settings...", 5, 6))
                DatabaseConfig.seedDatabase(database)
            } else {
                onProgress(DatabaseInitializationProgress("Preparing repositories...", 5, 6))
            }

            onProgress(DatabaseInitializationProgress("Finishing database startup...", 6, 6))
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
            require(dbFile.exists()) { "Database does not exist: ${location.name}" }

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

    override suspend fun databaseSizeBytes(location: DbLocation): Long? =
        withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(location.name)
            if (!dbFile.exists()) {
                null
            } else {
                // File.length() is 0 for absent sidecars, so no existence guard is needed.
                dbFile.length() + File("${dbFile.path}-wal").length() + File("${dbFile.path}-shm").length()
            }
        }

    override suspend fun snapshot(database: MoneyManagerDatabaseWrapper): ByteArray =
        withContext(Dispatchers.IO) {
            // VACUUM INTO requires the target file to not already exist.
            val tempFile = File.createTempFile("mm-snapshot", ".db", context.cacheDir)
            tempFile.delete()
            try {
                database.executeWithParams("VACUUM INTO ?", 1, listOf(tempFile.absolutePath))
                tempFile.readBytes()
            } finally {
                tempFile.delete()
            }
        }

    override suspend fun restore(
        location: DbLocation,
        bytes: ByteArray,
    ): Unit =
        withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(location.name)
            dbFile.parentFile?.mkdirs()
            // Drop stale WAL/SHM sidecars so the restored file isn't shadowed by an old write-ahead log.
            // Fail if a surviving sidecar can't be removed (matches the JVM impl's deleteIfExists), since
            // SQLite would otherwise replay the old WAL and silently shadow the restored main file.
            deleteSidecarOrThrow("${dbFile.path}-wal")
            deleteSidecarOrThrow("${dbFile.path}-shm")
            dbFile.writeBytes(bytes)
        }

    private fun deleteSidecarOrThrow(path: String) {
        val file = File(path)
        check(!file.exists() || file.delete()) { "Failed to delete stale database sidecar: $path" }
    }
}
