package com.moneymanager.database

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Creation decides create-vs-open by whether the file exists, so a creation that fails must leave no file: one left
 * behind would be opened next time as an existing database with no schema.
 */
class FailedDatabaseCreationTest {
    @Test
    fun `a failed creation leaves no file and the next open creates the database`() =
        runTest {
            val location = createTestDatabaseLocation()
            try {
                val failure =
                    assertFailsWith<IllegalStateException> {
                        JvmDatabaseManager(currenciesToSeed = { error("seeding failed") }).openDatabase(location)
                    }
                assertEquals("seeding failed", failure.message)
                assertFalse(SystemFileSystem.exists(Path(location.path)))
                assertFalse(SystemFileSystem.exists(Path("${location.path}-wal")))
                assertFalse(SystemFileSystem.exists(Path("${location.path}-shm")))

                val database = JvmDatabaseManager(currenciesToSeed = { emptyList() }).openDatabase(location)
                try {
                    // The schema exists: a fresh database has no balances, rather than no table to read them from.
                    assertEquals(emptyList(), database.transferSelectQueries.selectAllBalances().awaitAsList())
                } finally {
                    database.close()
                }
            } finally {
                deleteTestDatabase(location)
            }
        }
}
