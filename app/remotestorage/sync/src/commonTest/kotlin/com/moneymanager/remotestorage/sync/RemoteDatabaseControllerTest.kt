package com.moneymanager.remotestorage.sync

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    @Test
    fun uploadDoesNotReportRemoteChanged() =
        runTest {
            withController { controller, _, database, location ->
                controller.createRemote("in-memory", null, "db.mmdb", location, database, "pw")
                database.bumpDataChange()
                controller.refreshLocalDirty(database)
                assertEquals(SyncStatus.LOCAL_AHEAD, controller.syncState.value.status)

                assertEquals(SyncResult.UPLOADED, controller.syncNow(database))

                // Our own upload must not look like an external change on the next check.
                assertFalse(controller.remoteChanged(), "our own push should not register as a remote change")
                controller.checkRemote(database)
                assertEquals(SyncStatus.IN_SYNC, controller.syncState.value.status)
            }
        }

    @Test
    fun checkRemoteDetectsExternalPush() =
        runTest {
            withController { controller, provider, database, location ->
                val binding = controller.createRemote("in-memory", null, "db.mmdb", location, database, "pw")
                provider.externalPush(binding.remoteFileId, byteArrayOf(1, 2, 3))

                controller.checkRemote(database)

                val state = controller.syncState.value
                assertEquals(SyncStatus.REMOTE_AHEAD, state.status)
                assertTrue(state.editingLocked)
                assertTrue(state.canDownload)
                assertFalse(state.canUpload)
            }
        }

    @Test
    fun localEditMakesLocalAhead() =
        runTest {
            withController { controller, _, database, location ->
                controller.createRemote("in-memory", null, "db.mmdb", location, database, "pw")
                database.bumpDataChange()

                controller.refreshLocalDirty(database)

                val state = controller.syncState.value
                assertEquals(SyncStatus.LOCAL_AHEAD, state.status)
                assertTrue(state.canUpload)
                assertFalse(state.canDownload)
                assertFalse(state.editingLocked)
            }
        }

    @Test
    fun localEditPlusExternalPushIsConflict() =
        runTest {
            withController { controller, provider, database, location ->
                val binding = controller.createRemote("in-memory", null, "db.mmdb", location, database, "pw")
                database.bumpDataChange()
                provider.externalPush(binding.remoteFileId, byteArrayOf(9))

                controller.checkRemote(database)

                val state = controller.syncState.value
                assertEquals(SyncStatus.CONFLICT, state.status)
                assertTrue(state.editingLocked)
                assertTrue(state.canUpload)
                assertTrue(state.canDownload)
            }
        }

    @Test
    fun guardedUploadAbortsWhenRemoteMoved() =
        runTest {
            withController { controller, provider, database, location ->
                val binding = controller.createRemote("in-memory", null, "db.mmdb", location, database, "pw")
                database.bumpDataChange()
                val externalBytes = byteArrayOf(7, 7, 7)
                provider.externalPush(binding.remoteFileId, externalBytes)

                // Guarded upload must refuse rather than clobber the external content.
                assertEquals(SyncResult.BLOCKED, controller.syncNow(database))
                assertEquals(externalBytes.toList(), provider.download(binding.remoteFileId).toList())

                // Forcing overwrites the remote with our copy.
                assertEquals(SyncResult.UPLOADED, controller.syncNow(database, force = true))
                assertNotEquals(externalBytes.toList(), provider.download(binding.remoteFileId).toList())
            }
        }

    @Test
    fun revisionBaselineSurvivesRestart() =
        runTest {
            val manager = createTestDatabaseManager()
            val location = createTestDatabaseLocation()
            try {
                val database = manager.openDatabase(location)
                val provider = InMemoryStorageProvider()
                // A shared settings store is what carries the synced-revision baseline across "restarts".
                val sync = RemoteDatabaseSyncService(manager, InMemoryLocalSettings())
                val controller = RemoteDatabaseController(sync, SingleProviderFactory(provider))
                val binding = controller.createRemote("in-memory", null, "db.mmdb", location, database, "pw")

                // Another device pushes while we are "closed". Re-push the existing (valid) encrypted
                // archive so resume's password check still passes; only the revision id changes.
                provider.externalPush(binding.remoteFileId, provider.download(binding.remoteFileId))

                // A fresh controller over the same persisted settings resumes and still detects the change.
                val fresh = RemoteDatabaseController(sync, SingleProviderFactory(provider))
                assertTrue(fresh.resume("pw"))
                fresh.checkRemote(database)
                assertEquals(SyncStatus.REMOTE_AHEAD, fresh.syncState.value.status)

                database.close()
            } finally {
                deleteTestDatabase(location)
            }
        }

    @Test
    fun syncedTokenPersistsForCrossSessionDirtyDetection() =
        runTest {
            val manager = createTestDatabaseManager()
            val location = createTestDatabaseLocation()
            try {
                val database = manager.openDatabase(location)
                val provider = InMemoryStorageProvider()
                // A shared settings store carries the upload baseline across "restarts".
                val sync = RemoteDatabaseSyncService(manager, InMemoryLocalSettings())
                val controller = RemoteDatabaseController(sync, SingleProviderFactory(provider))
                controller.createRemote("in-memory", null, "db.mmdb", location, database, "pw")

                // The data-change token at upload time is persisted in the binding.
                assertTrue(sync.activeBinding()?.syncedToken != null, "synced token should persist after create")

                // A fresh controller (no session, no password) adopts the kept local copy and, with the
                // persisted baseline, correctly reports it as unchanged.
                val fresh = RemoteDatabaseController(sync, SingleProviderFactory(provider))
                fresh.adoptLocalCache(database)
                assertFalse(fresh.hasActiveSession(), "adopting a local cache must not arm a session")
                assertFalse(fresh.hasUnsyncedChanges(database), "unedited working copy should not look dirty")

                // A local edit is detected as a change against that same persisted baseline.
                database.bumpDataChange()
                assertTrue(fresh.hasUnsyncedChanges(database), "edited working copy should look dirty across restart")

                database.close()
            } finally {
                deleteTestDatabase(location)
            }
        }

    private suspend fun withController(
        block: suspend (
            controller: RemoteDatabaseController,
            provider: InMemoryStorageProvider,
            database: MoneyManagerDatabaseWrapper,
            location: DbLocation,
        ) -> Unit,
    ) {
        val manager = createTestDatabaseManager()
        val location = createTestDatabaseLocation()
        try {
            val database = manager.openDatabase(location)
            val provider = InMemoryStorageProvider()
            val sync = RemoteDatabaseSyncService(manager, InMemoryLocalSettings())
            val controller = RemoteDatabaseController(sync, SingleProviderFactory(provider))
            try {
                block(controller, provider, database, location)
            } finally {
                database.close()
            }
        } finally {
            deleteTestDatabase(location)
        }
    }
}
