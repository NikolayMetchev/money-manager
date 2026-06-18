package com.moneymanager.remotestorage.sync

import com.moneymanager.archive.ArchiveDecryptionException
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteDatabaseSyncServiceTest {
    @Test
    fun createRemoteThenHydrateRoundTripsTheDatabase() =
        runTest {
            val manager = createTestDatabaseManager()
            val sourceLocation = createTestDatabaseLocation()
            val cacheLocation = createTestDatabaseLocation()
            val targetLocation = createTestDatabaseLocation()
            try {
                val database = manager.openDatabase(sourceLocation)
                // A freshly created database is seeded with default currencies.
                val originalCurrencies = database.countRows("currency")
                assertTrue(originalCurrencies > 0, "seeded database should have currencies")

                val provider = InMemoryStorageProvider()
                val sync = RemoteDatabaseSyncService(manager, InMemoryLocalSettings())

                val binding = sync.createRemote(provider, "test.mmdb", cacheLocation, database, "pw")
                assertEquals(binding, sync.activeBinding(), "binding should be persisted")
                assertEquals(1, provider.list().size, "archive should be uploaded")

                val hydrated = sync.hydrate(provider, binding.copy(localCachePath = targetLocation.toString()), "pw")
                val restored = manager.openDatabase(hydrated)
                assertEquals(originalCurrencies, restored.countRows("currency"), "data should survive the round trip")
            } finally {
                deleteTestDatabase(sourceLocation)
                deleteTestDatabase(cacheLocation)
                deleteTestDatabase(targetLocation)
            }
        }

    @Test
    fun hydrateWithWrongPasswordFails() =
        runTest {
            val manager = createTestDatabaseManager()
            val sourceLocation = createTestDatabaseLocation()
            val cacheLocation = createTestDatabaseLocation()
            val targetLocation = createTestDatabaseLocation()
            try {
                val database = manager.openDatabase(sourceLocation)
                val provider = InMemoryStorageProvider()
                val sync = RemoteDatabaseSyncService(manager, InMemoryLocalSettings())
                val binding = sync.createRemote(provider, "test.mmdb", cacheLocation, database, "correct")

                assertFailsWith<ArchiveDecryptionException> {
                    sync.hydrate(provider, binding.copy(localCachePath = targetLocation.toString()), "wrong")
                }
            } finally {
                deleteTestDatabase(sourceLocation)
                deleteTestDatabase(cacheLocation)
                deleteTestDatabase(targetLocation)
            }
        }
}
