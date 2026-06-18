package com.moneymanager.remotestorage.sync

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.dbLocationFromString
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Session-scoped coordinator the UI/startup layer drives to put the active database under remote
 * storage. Wraps [RemoteDatabaseSyncService] with the provider reconstruction ([RemoteStorageProviderFactory])
 * and the in-memory password for the current run (never persisted), so "Sync now" and push-on-close
 * have everything they need without re-prompting.
 *
 * Deliberately free of Compose so it stays unit-testable.
 */
class RemoteDatabaseController(
    private val syncService: RemoteDatabaseSyncService,
    val providerFactory: RemoteStorageProviderFactory,
) {
    private data class Session(
        val provider: RemoteStorageProvider,
        val binding: RemoteDatabaseBinding,
        val password: String,
    )

    private var session: Session? = null
    private var syncedToken: Long? = null

    /** The persisted binding for the active database, if it is remote-backed. */
    fun activeBinding(): RemoteDatabaseBinding? = syncService.activeBinding()

    /** True once a remote database has been created/opened/restored this run (password held in memory). */
    fun hasActiveSession(): Boolean = session != null

    /** True once a sync baseline has been captured for this session (see [markSynced]). */
    fun hasSyncBaseline(): Boolean = syncedToken != null

    /** Records the current database state as "fully synced" (call when the live DB matches the remote). */
    suspend fun markSynced(database: MoneyManagerDatabaseWrapper) {
        syncedToken = currentToken(database)
    }

    /** True if [database] has logical changes that haven't been pushed since the last [markSynced]. */
    suspend fun hasUnsyncedChanges(database: MoneyManagerDatabaseWrapper): Boolean {
        val baseline = syncedToken ?: return false
        return currentToken(database) != baseline
    }

    private suspend fun currentToken(database: MoneyManagerDatabaseWrapper): Long =
        withContext(Dispatchers.Default) { database.dataChangeToken() }

    /**
     * Ensures there is a usable session with [providerId] for the given [config], performing interactive
     * sign-in if needed (e.g. the Google Drive consent flow). Lets a setup wizard validate the connection
     * before collecting an archive name/password. Throws if authentication fails.
     */
    suspend fun signInTo(
        providerId: String,
        config: String?,
    ) {
        resolve(providerId, config)
    }

    /** Lists the archives stored by [providerId] (signing in if needed) for an open-from-remote picker. */
    suspend fun list(
        providerId: String,
        config: String?,
    ): List<RemoteFile> {
        val provider = resolve(providerId, config)
        return provider.list()
    }

    /** Uploads the open [database] as a new remote archive and makes it the active remote-backed database. */
    suspend fun createRemote(
        providerId: String,
        config: String?,
        remoteName: String,
        localCachePath: DbLocation,
        database: MoneyManagerDatabaseWrapper,
        password: String,
        onProgress: (SyncProgress) -> Unit = {},
    ): RemoteDatabaseBinding {
        val provider = resolve(providerId, config)
        val binding = syncService.createRemote(provider, remoteName, localCachePath, database, password, config, onProgress)
        session = Session(provider, binding, password)
        markSynced(database)
        return binding
    }

    /**
     * Downloads + rehydrates an existing remote archive into [localCachePath], persists the binding and
     * returns the [DbLocation] to switch the app to.
     */
    suspend fun openRemote(
        providerId: String,
        config: String?,
        remoteFile: RemoteFile,
        localCachePath: DbLocation,
        password: String,
        onProgress: (SyncProgress) -> Unit = {},
    ): DbLocation {
        val provider = resolve(providerId, config)
        val binding = RemoteDatabaseBinding(providerId, remoteFile.id, remoteFile.name, localCachePath.toString(), config)
        val location = syncService.hydrate(provider, binding, password, onProgress)
        syncService.bind(binding)
        session = Session(provider, binding, password)
        return location
    }

    /**
     * Restores the persisted [binding] on startup (requires the user's [password]); returns the
     * [DbLocation] to open.
     */
    suspend fun restore(
        binding: RemoteDatabaseBinding,
        password: String,
        onProgress: (SyncProgress) -> Unit = {},
    ): DbLocation {
        val provider = resolve(binding.providerId, binding.providerConfig)
        val location = syncService.hydrate(provider, binding, password, onProgress)
        session = Session(provider, binding, password)
        return location
    }

    /**
     * Resumes syncing for the already-bound database by verifying [password] against the remote
     * archive (without overwriting the local copy). Returns true and arms the session on success.
     */
    suspend fun resume(password: String): Boolean {
        val binding = syncService.activeBinding() ?: return false
        val provider = resolve(binding.providerId, binding.providerConfig)
        if (!syncService.verify(provider, binding, password)) return false
        session = Session(provider, binding, password)
        return true
    }

    /**
     * Pushes the open [database] to the bound remote file. No-op if there is no active session. Set
     * [rebuildViews] false on close (the local copy is about to be discarded) to skip the rebuild.
     */
    suspend fun syncNow(
        database: MoneyManagerDatabaseWrapper,
        rebuildViews: Boolean = true,
        onProgress: (SyncProgress) -> Unit = {},
    ) {
        val current = session ?: return
        syncService.push(current.provider, current.binding, database, current.password, rebuildViews, onProgress)
        markSynced(database)
    }

    /**
     * For token-based providers, the epoch-millis instant the current session's access token expires
     * (it refreshes silently after that), or null if there's no session or the provider has no token.
     */
    suspend fun accessTokenExpiresAtEpochMs(): Long? = session?.provider?.accessTokenExpiresAtEpochMs()

    /** On-disk size in bytes of the local working copy at [location] (incl. WAL/SHM), or null. */
    suspend fun localDatabaseSize(location: DbLocation): Long? = syncService.localDatabaseSize(location)

    /** Size in bytes of the bound (compressed + encrypted) remote archive, or null if not bound/found. */
    suspend fun remoteArchiveSize(): Long? {
        val binding = syncService.activeBinding() ?: return null
        val provider = resolve(binding.providerId, binding.providerConfig)
        return provider.stat(binding.remoteFileId)?.sizeBytes
    }

    /**
     * Deletes the local working copy of the bound database (the binding itself is kept, so the next
     * launch restores it from the remote). No-op if there is no active binding. Call after the
     * database connection is closed and a final [syncNow] has succeeded.
     */
    suspend fun deleteLocalCache() {
        val binding = syncService.activeBinding() ?: return
        syncService.deleteLocal(dbLocationFromString(binding.localCachePath))
    }

    /** Detaches the active database from remote storage (keeps the local working copy). */
    fun unbind() {
        syncService.clearBinding()
        session = null
    }

    private suspend fun resolve(
        providerId: String,
        config: String?,
    ): RemoteStorageProvider {
        val provider = providerFactory.create(providerId, config)
        if (!provider.isSignedIn()) provider.signIn()
        return provider
    }
}
