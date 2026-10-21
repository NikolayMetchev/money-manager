package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.coroutines.ioDispatcher
import com.moneymanager.currency.IsoCurrency
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.defaultFileDatabaseLocation
import com.moneymanager.files.absolutePath
import com.moneymanager.files.readBytes
import com.moneymanager.files.writeAtomically
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * A [DatabaseManager] for databases that are plain files at a path (desktop JVM and native). Everything but
 * the SQLite driver is shared, so the desktop app and the CLI treat a database file identically.
 *
 * **Create-vs-open is decided by whether the file exists**, never by SQLite's `user_version`: the desktop
 * app has never set it, so every database it created reads as version 0. A driver that took 0 to mean
 * "empty" would try to create the schema again over a desktop-created file. There is no DB versioning yet
 * (#426), so nothing else reads `user_version` either.
 *
 * @param currenciesToSeed deferred so the currency list is only built when a fresh database is seeded;
 *   tests inject a minimal list (see :test:app:db's createTestDatabaseManager).
 */
abstract class FileDatabaseManager(
    private val currenciesToSeed: () -> List<IsoCurrency>,
) : DatabaseManager {
    /**
     * Opens a SQLite driver on [file] (an absolute path whose parent directory exists), with foreign keys,
     * WAL and [DatabaseConfig.BUSY_TIMEOUT_MS] applied. The driver must not create or migrate the schema
     * itself; [openDatabaseWithProgress] does that.
     */
    protected abstract fun openDriver(file: Path): SqlDriver

    override suspend fun openDatabase(location: DbLocation): MoneyManagerDatabaseWrapper = openDatabaseWithProgress(location) {}

    override suspend fun openDatabaseWithProgress(
        location: DbLocation,
        onProgress: (Progress) -> Unit,
    ): MoneyManagerDatabaseWrapper =
        withContext(ioDispatcher) {
            onProgress(Progress.counted("Checking for an existing database...", 1, OPEN_STEPS))
            val file = location.file
            val isNewDatabase = !SystemFileSystem.exists(file)

            onProgress(Progress.counted("Preparing the database folder...", 2, OPEN_STEPS))
            file.parent?.let { SystemFileSystem.createDirectories(it) }

            onProgress(Progress.counted("Opening the SQLite database...", 3, OPEN_STEPS))
            val driver = openDriver(file)

            val database = MoneyManagerDatabaseWrapper(driver)
            if (isNewDatabase) {
                var created = false
                try {
                    createSchema(database, currenciesToSeed, onProgress)
                    created = true
                } finally {
                    // Close the driver first, so it releases the file.
                    if (!created) {
                        database.close()
                        (listOf(file) + location.sidecars).forEach { SystemFileSystem.delete(it, mustExist = false) }
                    }
                }
            } else {
                reportExisting(onProgress)
            }
            onProgress(Progress.counted("Finishing database startup...", OPEN_STEPS, OPEN_STEPS))
            database
        }

    // An unusable persisted path counts as missing, so startup never crashes on it.
    override suspend fun databaseExists(location: DbLocation): Boolean =
        withContext(ioDispatcher) {
            runCatching { SystemFileSystem.exists(location.file) }.getOrDefault(false)
        }

    override fun getDefaultLocation() = defaultFileDatabaseLocation()

    override suspend fun backupDatabase(location: DbLocation): DbLocation =
        withContext(ioDispatcher) {
            val file = location.file
            require(SystemFileSystem.exists(file)) { "Database does not exist at: $location" }

            // The WAL/SHM sidecars move with the main file: committed pages can still be in the WAL, and a
            // sidecar left behind would be paired with the fresh database created at this path next.
            val backup = DbLocation("$file.backup")
            (listOf(backup.file) + backup.sidecars).forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.atomicMove(file, backup.file)
            location.sidecars.zip(backup.sidecars).forEach { (sidecar, backupSidecar) ->
                if (SystemFileSystem.exists(sidecar)) SystemFileSystem.atomicMove(sidecar, backupSidecar)
            }
            backup
        }

    override suspend fun deleteDatabase(location: DbLocation): Unit =
        withContext(ioDispatcher) {
            // Remove the main file and its WAL/SHM sidecars.
            (listOf(location.file) + location.sidecars).forEach { SystemFileSystem.delete(it, mustExist = false) }
        }

    override suspend fun databaseSizeBytes(location: DbLocation): Long? =
        withContext(ioDispatcher) {
            SystemFileSystem.metadataOrNull(location.file)?.let { main ->
                main.size + location.sidecars.sumOf { SystemFileSystem.metadataOrNull(it)?.size ?: 0L }
            }
        }

    override suspend fun snapshot(database: MoneyManagerDatabaseWrapper): ByteArray =
        withContext(ioDispatcher) {
            // VACUUM INTO requires the target file to not already exist, so pick a fresh name.
            val snapshot =
                Path(
                    absolutePath(SystemTemporaryDirectory),
                    "mm-snapshot-${Random.nextLong().toULong().toString(SNAPSHOT_NAME_RADIX)}.db",
                )
            try {
                database.executeWithParams("VACUUM INTO ?", 1, listOf(snapshot.toString()))
                readBytes(snapshot)
            } finally {
                SystemFileSystem.delete(snapshot, mustExist = false)
            }
        }

    override suspend fun restore(
        location: DbLocation,
        bytes: ByteArray,
    ): Unit =
        withContext(ioDispatcher) {
            // Drop stale WAL/SHM sidecars so the restored file isn't shadowed by an old write-ahead log.
            location.sidecars.forEach { SystemFileSystem.delete(it, mustExist = false) }
            writeAtomically(location.file, bytes)
        }

    private val DbLocation.file: Path
        get() = absolutePath(Path(path))

    private val DbLocation.sidecars: List<Path>
        get() = listOf(Path("$path-wal"), Path("$path-shm"))

    private companion object {
        const val SNAPSHOT_NAME_RADIX = 36
    }
}
