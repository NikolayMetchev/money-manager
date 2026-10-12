package com.moneymanager.session

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.testing.InMemoryLocalSettings
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.database.DatabaseManager
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.importengineapi.EditingLockedException
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.localsettings.KEY_LAST_DATABASE
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.sync.RemoteDatabaseBinding
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.RemoteDatabaseSyncService
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseSessionsTest {
    private val manager: DatabaseManager = createTestDatabaseManager()
    private val settings = InMemoryLocalSettings()
    private val vault: CredentialVault = inMemoryCredentialVault()

    private fun sessions(controller: RemoteDatabaseController? = null) = DatabaseSessions(manager, settings, vault, controller)

    private fun boundTo(location: DbLocation) = "$location.credentials"

    private fun controller(provider: RemoteStorageProvider = OneFileProvider()) =
        RemoteDatabaseController(RemoteDatabaseSyncService(manager, settings), SingleProviderFactory(provider))

    private fun withLocations(
        count: Int,
        block: suspend (List<DbLocation>) -> Unit,
    ) = runTest {
        val locations = List(count) { createTestDatabaseLocation() }
        try {
            block(locations)
        } finally {
            locations.forEach(::deleteTestDatabase)
        }
    }

    @Test
    fun explicitLocationWinsOverEverything() =
        withLocations(2) { (stored, explicit) ->
            manager.openDatabase(stored).close()
            settings.putString(KEY_LAST_DATABASE, stored.toString())

            assertEquals(StartupTarget.Open(explicit), sessions().resolveStartup(explicit))
            assertEquals(boundTo(explicit), vault.defaultPath())
        }

    @Test
    fun opensTheLastDatabaseWhenItExists() =
        withLocations(1) { (stored) ->
            manager.openDatabase(stored).close()
            settings.putString(KEY_LAST_DATABASE, stored.toString())

            assertEquals(StartupTarget.Open(stored), sessions().resolveStartup())
            assertEquals(boundTo(stored), vault.defaultPath())
        }

    @Test
    fun asksForADatabaseWhenTheLastOneIsGone() =
        withLocations(1) { (missing) ->
            settings.putString(KEY_LAST_DATABASE, missing.toString())

            assertEquals(StartupTarget.ChooseDatabase, sessions().resolveStartup())
        }

    @Test
    fun opensTheKeptWorkingCopyOfACloudDatabase() =
        withLocations(1) { (cache) ->
            manager.openDatabase(cache).close()
            val controller = controller()
            RemoteDatabaseSyncService(manager, settings).bind(binding(cache))

            assertEquals(StartupTarget.Open(cache, remoteCache = true), sessions(controller).resolveStartup())
            assertEquals(boundTo(cache), vault.defaultPath())
        }

    @Test
    fun needsThePasswordWhenACloudDatabaseHasNoLocalCopy() =
        withLocations(1) { (cache) ->
            val binding = binding(cache)
            RemoteDatabaseSyncService(manager, settings).bind(binding)

            assertEquals(StartupTarget.NeedsRemoteUnlock(binding), sessions(controller()).resolveStartup())
            assertEquals(boundTo(cache), vault.defaultPath())
        }

    @Test
    fun openBuildsTheSessionAndRemembersTheDatabase() =
        withLocations(1) { (location) ->
            val session = sessions().open(location)
            try {
                assertEquals(location, session.location)
                assertTrue(session.services.deviceId.id > 0)
                assertEquals(location.toString(), settings.getString(KEY_LAST_DATABASE))
                assertEquals(boundTo(location), vault.defaultPath())
            } finally {
                session.database.close()
            }
        }

    @Test
    fun theEngineRefusesWritesWhileTheCloudCopyIsAhead() =
        withLocations(1) { (location) ->
            val provider = OneFileProvider()
            val controller = controller(provider)
            val session = sessions(controller).open(location)
            try {
                controller.createRemote(OneFileProvider.ID, null, "db.mmdb", location, session.database, "pw")
                session.importEngine.import(ImportBatch())

                // Another device pushes: the remote is ahead, so this device must download before editing.
                provider.revision++
                controller.checkRemote(session.database)
                assertFailsWith<EditingLockedException> { session.importEngine.import(ImportBatch()) }
            } finally {
                session.database.close()
            }
        }

    @Test
    fun restoreRemoteOpensTheHydratedCopyInSync() =
        withLocations(2) { (source, cache) ->
            val provider = OneFileProvider()
            val creator = controller(provider)
            val created = sessions(creator).open(source)
            val binding = creator.createRemote(OneFileProvider.ID, null, "db.mmdb", source, created.database, "pw")
            created.database.close()

            val restorer = controller(provider)
            val session = sessions(restorer).restoreRemote(binding.copy(localCachePath = cache.toString()), "pw")
            try {
                assertEquals(cache, session.location)
                assertTrue(restorer.hasSyncBaseline())
                assertEquals(cache.toString(), settings.getString(KEY_LAST_DATABASE))
            } finally {
                session.database.close()
            }
        }

    @Test
    fun recreateKeepsABackupAndOpensAFreshDatabase() =
        withLocations(1) { (location) ->
            sessions().open(location).database.close()

            val session = sessions().recreate(location, backup = true)
            try {
                assertTrue(manager.databaseExists(DbLocation("$location.backup")))
            } finally {
                session.database.close()
                deleteTestDatabase(DbLocation("$location.backup"))
            }
        }

    private fun binding(cache: DbLocation) =
        RemoteDatabaseBinding(
            providerId = OneFileProvider.ID,
            providerConfig = null,
            remoteFileId = OneFileProvider.FILE_ID,
            remoteName = "db.mmdb",
            localCachePath = cache.toString(),
        )
}

/** Holds a single remote file whose [revision] a test can bump, as if another device had pushed. */
private class OneFileProvider : RemoteStorageProvider {
    override val id: String = ID
    override val displayName: String = "One file"
    var revision = 0
    private var bytes: ByteArray? = null

    override suspend fun isSignedIn(): Boolean = true

    override suspend fun signIn() = Unit

    override suspend fun signOut() = Unit

    override suspend fun list(): List<RemoteFile> = listOfNotNull(stat(FILE_ID))

    override suspend fun stat(fileId: String): RemoteFile? =
        bytes?.let { RemoteFile(FILE_ID, "db.mmdb", it.size.toLong(), revisionId = "rev-$revision") }

    override suspend fun download(fileId: String): ByteArray = checkNotNull(bytes)

    override suspend fun upload(
        fileId: String?,
        name: String,
        bytes: ByteArray,
    ): RemoteFile {
        this.bytes = bytes
        revision++
        return checkNotNull(stat(FILE_ID))
    }

    override suspend fun delete(fileId: String) {
        bytes = null
    }

    companion object {
        const val ID = "one-file"
        const val FILE_ID = "file-0"
    }
}

private class SingleProviderFactory(
    private val provider: RemoteStorageProvider,
) : RemoteStorageProviderFactory {
    override fun types(): List<RemoteStorageType> = listOf(RemoteStorageType(provider.id, provider.displayName))

    override fun create(
        providerId: String,
        config: String?,
        subfolder: String?,
    ): RemoteStorageProvider = provider
}
