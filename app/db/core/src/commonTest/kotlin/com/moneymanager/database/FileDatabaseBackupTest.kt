package com.moneymanager.database

import com.moneymanager.domain.model.DbLocation
import com.moneymanager.files.readText
import com.moneymanager.files.writeText
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.testDatabasesAreFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The startup "back up and recreate" path backs a database up and then creates a fresh one at the same path, so
 * the backup must take the WAL/SHM sidecars with it: committed pages can still be in the WAL, and a sidecar left
 * behind would be paired with the new database.
 */
class FileDatabaseBackupTest {
    @Test
    fun `backup moves the WAL and SHM sidecars with the database`() =
        runTest {
            if (!testDatabasesAreFiles) return@runTest
            val location = createTestDatabaseLocation()
            val manager = createTestDatabaseManager()
            var backup: DbLocation? = null
            try {
                manager.openDatabase(location).close()
                // Stand-ins for sidecars a database closed without a checkpoint leaves behind.
                writeText(Path("${location.path}-wal"), "wal")
                writeText(Path("${location.path}-shm"), "shm")

                backup = manager.backupDatabase(location)

                assertFalse(SystemFileSystem.exists(Path(location.path)))
                assertFalse(SystemFileSystem.exists(Path("${location.path}-wal")))
                assertFalse(SystemFileSystem.exists(Path("${location.path}-shm")))
                assertEquals("wal", readText(Path("${backup.path}-wal")))
                assertEquals("shm", readText(Path("${backup.path}-shm")))

                manager.openDatabase(location).close()
            } finally {
                deleteTestDatabase(location)
                backup?.let(::deleteTestDatabase)
            }
        }
}
