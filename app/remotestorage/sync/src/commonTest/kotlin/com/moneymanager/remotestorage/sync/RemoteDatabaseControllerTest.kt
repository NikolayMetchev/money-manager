package com.moneymanager.remotestorage.sync

import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteDatabaseControllerTest {
    @Test
    fun deleteLocalCacheRemovesWorkingCopy() =
        runTest {
            val manager = createTestDatabaseManager()
            val location = createTestDatabaseLocation()
            try {
                val database = manager.openDatabase(location)
                val sync = RemoteDatabaseSyncService(manager, InMemoryLocalSettings())
                val controller = RemoteDatabaseController(sync, SingleProviderFactory(InMemoryStorageProvider()))
                controller.createRemote("in-memory", null, "db.mmenc", location, database, "pw")
                assertTrue(manager.databaseSizeBytes(location) != null, "working copy should exist after create")

                database.close()
                controller.deleteLocalCache()
                assertNull(manager.databaseSizeBytes(location), "working copy should be deleted")
            } finally {
                deleteTestDatabase(location)
            }
        }

    @Test
    fun createSyncNowAndRestoreFlow() =
        runTest {
            val manager = createTestDatabaseManager()
            val sourceLocation = createTestDatabaseLocation()
            val cacheLocation = createTestDatabaseLocation()
            val targetLocation = createTestDatabaseLocation()
            try {
                val database = manager.openDatabase(sourceLocation)
                val originalCurrencies = database.countRows("currency")

                val provider = InMemoryStorageProvider()
                val sync = RemoteDatabaseSyncService(manager, InMemoryLocalSettings())
                val controller = RemoteDatabaseController(sync, SingleProviderFactory(provider))

                assertFalse(controller.hasActiveSession())
                val binding = controller.createRemote("in-memory", null, "db.mmdb", cacheLocation, database, "pw")
                assertTrue(controller.hasActiveSession())
                assertEquals(binding, controller.activeBinding())

                // Sync now re-uploads in place without error.
                controller.syncNow(database)
                assertEquals(1, provider.list().size)

                // A fresh controller restores the binding given the password.
                val freshController = RemoteDatabaseController(sync, SingleProviderFactory(provider))
                val restoredLocation = freshController.restore(binding.copy(localCachePath = targetLocation.toString()), "pw")
                val restored = manager.openDatabase(restoredLocation)
                try {
                    assertEquals(originalCurrencies, restored.countRows("currency"))
                    assertTrue(freshController.hasActiveSession())
                } finally {
                    restored.close()
                }
                database.close()
            } finally {
                deleteTestDatabase(sourceLocation)
                deleteTestDatabase(cacheLocation)
                deleteTestDatabase(targetLocation)
            }
        }
}
