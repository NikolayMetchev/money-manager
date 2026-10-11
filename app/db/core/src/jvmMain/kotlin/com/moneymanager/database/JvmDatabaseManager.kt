package com.moneymanager.database

import com.moneymanager.currency.IsoCurrency
import com.moneymanager.database.sql.seed.MoneyManagerDatabase
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.defaultFileDatabaseLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.exists

private val DbLocation.jdbcUrl: String
    get() = "jdbc:sqlite:$path"

private val DbLocation.file: Path
    get() = Paths.get(path)

/**
 * JVM implementation of DatabaseManager.
 * Manages SQLite database files on the file system.
 * Stateless - does not track open databases.
 */
class JvmDatabaseManager(
    // Deferred so the currency list is only built when a fresh database is seeded;
    // tests inject a minimal list (see :test:app:db's createTestDatabaseManager).
    private val currenciesToSeed: () -> List<IsoCurrency> = { DatabaseConfig.allCurrencies },
) : DatabaseManager {
    override suspend fun openDatabase(location: DbLocation): MoneyManagerDatabaseWrapper = openDatabaseWithProgress(location) {}

    override suspend fun openDatabaseWithProgress(
        location: DbLocation,
        onProgress: (DatabaseInitializationProgress) -> Unit,
    ): MoneyManagerDatabaseWrapper =
        withContext(Dispatchers.IO) {
            onProgress(DatabaseInitializationProgress("Checking for an existing database...", 1, 7))
            // Auto-detect if this is a new database
            val isNewDatabase = !location.file.exists()

            onProgress(DatabaseInitializationProgress("Preparing the database folder...", 2, 7))
            // Create parent directories if they don't exist
            location.file.parent?.let { parentDir ->
                if (!parentDir.exists()) {
                    Files.createDirectories(parentDir)
                }
            }

            onProgress(DatabaseInitializationProgress("Opening the SQLite database...", 3, 7))
            // Create driver with JDBC URL and properties to enable foreign keys
            // Note: foreign_keys must be set via Properties for file-based databases
            // See: https://github.com/sqldelight/sqldelight/issues/2421
            val properties =
                Properties().apply {
                    put("foreign_keys", "true")
                }
            val driver = CachingJdbcSqliteDriver(location.jdbcUrl, properties)

            onProgress(DatabaseInitializationProgress("Applying database settings...", 4, 7))
            // Apply cross-platform pragmas (foreign_keys already set via Properties above).
            // WAL mode and busy_timeout are JVM-only: on Android WAL is enabled via
            // enableWriteAheadLogging() and the connection pool handles contention differently.
            DatabaseConfig.connectionPragmas
                .filterNot { it.contains("foreign_keys", ignoreCase = true) }
                .plus(listOf("PRAGMA journal_mode = WAL", "PRAGMA busy_timeout = 5000"))
                .forEach { pragma ->
                    driver.execute(null, pragma, 0)
                }

            if (isNewDatabase) {
                onProgress(DatabaseInitializationProgress("Creating the database schema...", 5, 7))
                // Create schema for new database
                MoneyManagerDatabase.Schema.create(driver)
            } else {
                onProgress(DatabaseInitializationProgress("Checking the database schema...", 5, 7))
            }

            // Lookups, the system device and GBP are seeded inside Schema.create (the :app:db:seed module);
            // the rest of the bundled ISO 4217 list is seeded here for a freshly created database.
            val database = MoneyManagerDatabaseWrapper(driver)

            if (isNewDatabase) {
                onProgress(DatabaseInitializationProgress("Adding default currencies...", 6, 7))
                DatabaseConfig.seedCurrencies(database, currenciesToSeed())
            } else {
                onProgress(DatabaseInitializationProgress("Preparing repositories...", 6, 7))
            }
            onProgress(DatabaseInitializationProgress("Finishing database startup...", 7, 7))
            database
        }

    // An invalid persisted path (InvalidPathException) counts as missing, so startup never crashes on it.
    override suspend fun databaseExists(location: DbLocation): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { location.file.exists() }.getOrDefault(false)
        }

    override fun getDefaultLocation() = defaultFileDatabaseLocation()

    override suspend fun backupDatabase(location: DbLocation): DbLocation =
        withContext(Dispatchers.IO) {
            require(location.file.exists()) { "Database does not exist at: $location" }

            val backupPath = location.file.resolveSibling("${location.file.fileName}.backup")
            val backupLocation = DbLocation(backupPath.toString())

            // Delete existing backup if it exists
            if (backupLocation.file.exists()) {
                Files.delete(backupLocation.file)
            }

            // Move the database file to backup
            Files.move(location.file, backupLocation.file)

            backupLocation
        }

    override suspend fun deleteDatabase(location: DbLocation): Unit =
        withContext(Dispatchers.IO) {
            // Remove the main file and its WAL/SHM sidecars.
            listOf(location.file, Paths.get("${location.path}-wal"), Paths.get("${location.path}-shm"))
                .forEach { Files.deleteIfExists(it) }
        }

    override suspend fun databaseSizeBytes(location: DbLocation): Long? =
        withContext(Dispatchers.IO) {
            if (!location.file.exists()) {
                null
            } else {
                val sidecars = listOf("${location.path}-wal", "${location.path}-shm").map { Paths.get(it) }
                Files.size(location.file) + sidecars.sumOf { if (it.exists()) Files.size(it) else 0L }
            }
        }

    override suspend fun snapshot(database: MoneyManagerDatabaseWrapper): ByteArray =
        withContext(Dispatchers.IO) {
            // VACUUM INTO requires the target file to not already exist.
            val tempFile = Files.createTempFile("mm-snapshot", ".db")
            Files.delete(tempFile)
            try {
                database.executeWithParams("VACUUM INTO ?", 1, listOf(tempFile.toString()))
                Files.readAllBytes(tempFile)
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }

    override suspend fun restore(
        location: DbLocation,
        bytes: ByteArray,
    ): Unit =
        withContext(Dispatchers.IO) {
            location.file.parent?.let { parentDir ->
                if (!parentDir.exists()) {
                    Files.createDirectories(parentDir)
                }
            }
            // Drop stale WAL/SHM sidecars so the restored file isn't shadowed by an old write-ahead log.
            Files.deleteIfExists(Paths.get("${location.path}-wal"))
            Files.deleteIfExists(Paths.get("${location.path}-shm"))
            Files.write(location.file, bytes)
        }
}
