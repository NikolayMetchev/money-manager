package com.moneymanager.presenter.status

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.credentialvault.testing.InMemoryLocalSettings
import com.moneymanager.credentialvault.testing.TEST_VAULT_PASSWORD
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.credentialvault.testing.unlockedCredentialVault
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.importengineapi.createCurrency
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.RemoteDatabaseSyncService
import com.moneymanager.remotestorage.sync.SyncStatus
import com.moneymanager.remotestorage.testing.InMemoryStorageProvider
import com.moneymanager.remotestorage.testing.SingleProviderFactory
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val VERSION = AppVersion("9.9.9-test")

// Real time, not runTest's virtual clock: the database is read on real dispatchers and local changes are polled.
class StatusPresenterTest {
    @Test
    fun aLocalDatabaseHasNoCloudCopy() =
        withStatus(cloud = false) { session, remote, vault, scope ->
            present(session, remote, vault, scope).test(timeout = TIMEOUT) {
                val model = awaitItem()
                assertEquals(VERSION, model.version)
                assertEquals(session.location, model.location)
                assertNull(model.cloud)
                assertIs<VaultState.Unlocked>(model.vault)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun followsTheCloudCopyThroughEditsUploadsAndAnotherDevicesPush() =
        withStatus(cloud = true) { session, remote, vault, scope ->
            present(session, remote, vault, scope).test(timeout = TIMEOUT) {
                val synced = awaitItem()
                val cloud = checkNotNull(synced.cloud)
                assertEquals("db.mmdb", cloud.remoteName)
                assertTrue(cloud.connected)
                assertEquals(SyncStatus.IN_SYNC, cloud.sync.status)

                session.importEngine.createCurrency("ZZZ", "Test")
                val edited = awaitUntil { it.cloud?.sync?.status == SyncStatus.LOCAL_AHEAD }
                assertTrue(checkNotNull(edited.cloud).localChanges)

                edited.eventSink(StatusEvent.Upload())
                val uploaded = awaitUntil { it.busy == null && it.message != null }
                assertEquals(StatusMessage("Uploaded.", error = false), uploaded.message)
                awaitUntil { it.cloud?.let { c -> c.sync.status == SyncStatus.IN_SYNC && !c.localChanges } == true }

                provider.externalPush(checkNotNull(remote?.activeBinding()).remoteFileId, byteArrayOf(1, 2, 3))
                uploaded.eventSink(StatusEvent.CheckRemote)
                val pushed = awaitUntil { it.cloud?.sync?.status == SyncStatus.REMOTE_AHEAD && it.busy == null }
                assertTrue(checkNotNull(pushed.cloud).sync.editingLocked)

                pushed.eventSink(StatusEvent.Upload())
                val blocked = awaitUntil { it.busy == null && it.message != null }
                val refused = checkNotNull(blocked.message)
                assertTrue(refused.error)
                assertTrue(refused.text.startsWith("Another device changed the cloud copy"))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun locksAndUnlocksTheVault() =
        withStatus(cloud = false) { session, remote, vault, scope ->
            present(session, remote, vault, scope).test(timeout = TIMEOUT) {
                awaitItem().eventSink(StatusEvent.LockVault)
                val locked = awaitUntil { it.vault is VaultState.Locked && it.busy == null }

                locked.eventSink(StatusEvent.UnlockVault)
                // Whoever answers the vault's requests (the TUI's dialog) is asked, and the model follows.
                vault.unlockRequest.filterNotNull().first()
                vault.unlock(TEST_VAULT_PASSWORD)
                val unlocked = awaitUntil { it.vault is VaultState.Unlocked && it.busy == null }
                assertNull(unlocked.message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private val provider = InMemoryStorageProvider()

    private fun present(
        session: DatabaseSession,
        remote: RemoteDatabaseController?,
        vault: CredentialVault,
        scope: CoroutineScope,
    ) = moleculeFlow(RecompositionMode.Immediate) { presentStatus(session, remote, vault, VERSION, scope) }

    private suspend fun ReceiveTurbine<StatusModel>.awaitUntil(predicate: (StatusModel) -> Boolean): StatusModel {
        var last: StatusModel? = null
        try {
            var item = awaitItem()
            while (!predicate(item)) {
                last = item
                item = awaitItem()
            }
            return item
        } catch (timeout: AssertionError) {
            throw AssertionError("Still waiting; the last model was $last", timeout)
        }
    }

    // The presenter gets its own unlocked vault: opening a database rebinds the session's vault to that file.
    private fun withStatus(
        cloud: Boolean,
        test: suspend (DatabaseSession, RemoteDatabaseController?, CredentialVault, CoroutineScope) -> Unit,
    ) = runTest(timeout = WHOLE_TEST_TIMEOUT) {
        // Real time, one coroutine at a time, off runTest's scheduler: in the context, Turbine would collect on its
        // virtual clock, and the presenter's local-changes poll would spin without waiting. A hang fails the test
        // rather than the whole CI job.
        CoroutineScope(Dispatchers.Default.limitedParallelism(1)).async { runStatus(cloud, test) }.await()
    }

    private suspend fun runStatus(
        cloud: Boolean,
        test: suspend (DatabaseSession, RemoteDatabaseController?, CredentialVault, CoroutineScope) -> Unit,
    ) {
        val location = createTestDatabaseLocation()
        val manager = createTestDatabaseManager()
        val settings = InMemoryLocalSettings()
        val remote = RemoteDatabaseController(RemoteDatabaseSyncService(manager, settings), SingleProviderFactory(provider))
        val session = DatabaseSessions(manager, settings, inMemoryCredentialVault(), remote).open(location)
        // Actions run on the test's own one-at-a-time dispatcher. In parallel their state writes would race the
        // composition's cancellation: under Turbine's Unconfined collector, Molecule sends a frame inline from the global
        // snapshot lock's write observer while the cancelling thread holds the Recomposer's lock and wants the snapshot's.
        val scope = CoroutineScope(SupervisorJob() + currentCoroutineContext().minusKey(Job))
        try {
            if (cloud) remote.createRemote(provider.id, null, "db.mmdb", location, session.database, "pw")
            test(session, remote, unlockedCredentialVault(), scope)
        } finally {
            scope.cancel()
            session.database.close()
            deleteTestDatabase(location)
        }
    }

    private companion object {
        val TIMEOUT = 30.seconds
        val WHOLE_TEST_TIMEOUT = 3.minutes
    }
}
