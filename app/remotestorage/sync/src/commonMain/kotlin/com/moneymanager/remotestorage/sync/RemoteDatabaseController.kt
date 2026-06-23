package com.moneymanager.remotestorage.sync

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.dbLocationFromString
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The outcome of a guarded [RemoteDatabaseController.syncNow] (upload) attempt. */
enum class SyncResult {
    /** No active remote session; nothing was attempted. */
    NO_SESSION,

    /** The local database was pushed to the remote. */
    UPLOADED,

    /** The remote moved since the last sync, so the push was refused to avoid clobbering it. */
    BLOCKED,
}

/**
 * Session-scoped coordinator the UI/startup layer drives to put the active database under remote
 * storage. Wraps [RemoteDatabaseSyncService] with the provider reconstruction ([RemoteStorageProviderFactory])
 * and the in-memory password for the current run (never persisted), so "Sync now" and push-on-close
 * have everything they need without re-prompting.
 *
 * It also tracks the multi-device [syncState]: the local change baseline ([syncedToken]) and the remote
 * revision baseline ([syncedRevision], persisted in the binding). Remote-change detection is on demand
 * ([checkRemote] and the [syncNow] upload guard), never polled.
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
    private var syncedRevision: String? = null

    private val _syncState = MutableStateFlow(SyncState())

    /** Reactive view of how the local copy stands relative to the remote (drives the sync UI + lock). */
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** The persisted binding for the active database, if it is remote-backed. */
    fun activeBinding(): RemoteDatabaseBinding? = syncService.activeBinding()

    /** True once a remote database has been created/opened/restored this run (password held in memory). */
    fun hasActiveSession(): Boolean = session != null

    /** True once a sync baseline has been captured for this session (see [markSynced]). */
    fun hasSyncBaseline(): Boolean = syncedToken != null

    /** Records the current database state as "fully synced" (call when the live DB matches the remote). */
    suspend fun markSynced(database: MoneyManagerDatabaseWrapper) {
        syncedToken = currentToken(database)
        publish(localDirty = false, remoteChanged = false)
    }

    /** True if [database] has logical changes that haven't been pushed since the last [markSynced]. */
    suspend fun hasUnsyncedChanges(database: MoneyManagerDatabaseWrapper): Boolean {
        val baseline = syncedToken ?: return false
        return currentToken(database) != baseline
    }

    private suspend fun currentToken(database: MoneyManagerDatabaseWrapper): Long =
        withContext(Dispatchers.Default) { database.dataChangeToken() }

    /** Republishes [syncState] from the two change flags (status is NO_SESSION when no session is armed). */
    private fun publish(
        localDirty: Boolean,
        remoteChanged: Boolean,
    ) {
        val status =
            if (session == null) SyncStatus.NO_SESSION else SyncState.statusFor(localDirty, remoteChanged)
        _syncState.value = SyncState(status, localDirty, remoteChanged)
    }

    /**
     * Synchronously marks [syncState] busy so the UI can disable the upload/download actions the instant
     * one is triggered (before the longer async work and the switch to the loading screen). Cleared by the
     * next [publish] (e.g. once the download completes and the database is re-baselined).
     */
    fun beginBusy() {
        _syncState.value = _syncState.value.copy(busy = true)
    }

    /**
     * Recomputes the local-dirty flag (cheap, no network) and republishes [syncState], keeping the last
     * known remote-changed flag. Call this on a light cadence while a session is active.
     */
    suspend fun refreshLocalDirty(database: MoneyManagerDatabaseWrapper) {
        if (session == null) {
            publish(localDirty = false, remoteChanged = false)
            return
        }
        publish(localDirty = hasUnsyncedChanges(database), remoteChanged = _syncState.value.remoteChanged)
    }

    /**
     * Checks the remote for changes (a network `stat`) and republishes [syncState]. Invoked on demand
     * (the "Check remote for changes" button), on session arm, and after a download — never on a timer.
     * Propagates network/auth failures to the caller so the UI can report them.
     */
    suspend fun checkRemote(database: MoneyManagerDatabaseWrapper) {
        if (session == null) {
            publish(localDirty = false, remoteChanged = false)
            return
        }
        val changed = remoteChanged()
        publish(localDirty = hasUnsyncedChanges(database), remoteChanged = changed)
    }

    /**
     * Whether the remote head revision differs from the baseline this device last synced to. False when
     * there is no session or no baseline revision is known (can't tell → don't claim a change).
     */
    suspend fun remoteChanged(): Boolean {
        val current = session ?: return false
        val baseline = syncedRevision ?: return false
        val rev = current.provider.stat(current.binding.remoteFileId)?.revisionId
        return rev != null && rev != baseline
    }

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

    /**
     * Uploads the open [database] as a new remote archive and makes it the active remote-backed database.
     * Pass [overwriteFileId] to replace an existing remote file in place (after the user confirmed a name
     * collision) instead of creating a new one.
     */
    suspend fun createRemote(
        providerId: String,
        config: String?,
        remoteName: String,
        localCachePath: DbLocation,
        database: MoneyManagerDatabaseWrapper,
        password: String,
        overwriteFileId: String? = null,
        onProgress: (SyncProgress) -> Unit = {},
    ): RemoteDatabaseBinding {
        val provider = resolve(providerId, config)
        val binding =
            syncService.createRemote(provider, remoteName, localCachePath, database, password, config, overwriteFileId, onProgress)
        session = Session(provider, binding, password)
        syncedRevision = binding.syncedRevision
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
        // Seed the baseline from the picked file so detection still works if hydrate can't fetch a revision.
        val initial =
            RemoteDatabaseBinding(
                providerId = providerId,
                remoteFileId = remoteFile.id,
                remoteName = remoteFile.name,
                localCachePath = localCachePath.toString(),
                providerConfig = config,
                syncedRevision = remoteFile.revisionId,
            )
        val result = syncService.hydrate(provider, initial, password, onProgress)
        armHydratedSession(provider, initial, password, result.revisionId ?: remoteFile.revisionId)
        return result.location
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
        val result = syncService.hydrate(provider, binding, password, onProgress)
        // Keep the persisted baseline if this run couldn't fetch a revision.
        armHydratedSession(provider, binding, password, result.revisionId ?: binding.syncedRevision)
        return result.location
    }

    /**
     * Persists [revision] onto [binding], arms the session for [provider]/[password], and publishes the
     * in-sync state. Shared by [openRemote] and [restore] after a successful hydrate.
     */
    private fun armHydratedSession(
        provider: RemoteStorageProvider,
        binding: RemoteDatabaseBinding,
        password: String,
        revision: String?,
    ) {
        val armed = binding.copy(syncedRevision = revision)
        syncService.bind(armed)
        session = Session(provider, armed, password)
        syncedRevision = revision
        publish(localDirty = false, remoteChanged = false)
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
        syncedRevision = binding.syncedRevision
        publish(localDirty = false, remoteChanged = false)
        return true
    }

    /**
     * Pushes the open [database] to the bound remote file. Returns [SyncResult.NO_SESSION] when not
     * remote-backed. Unless [force] is true, it first re-checks the remote and returns
     * [SyncResult.BLOCKED] (without uploading) if another device pushed since the last sync.
     *
     * Note this is a best-effort guard, not an atomic conditional write: the Google Drive REST API v3 has
     * no If-Match/ETag/revision precondition, so a push that lands between this check and the upload is
     * still last-writer-wins. The check shrinks the window to near-zero for human-paced multi-device use;
     * it does not eliminate it. Set [rebuildViews] false on close (the local copy is about to be
     * discarded) to skip the materialized-view rebuild.
     */
    suspend fun syncNow(
        database: MoneyManagerDatabaseWrapper,
        force: Boolean = false,
        rebuildViews: Boolean = true,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult {
        val current = session ?: return SyncResult.NO_SESSION
        if (!force && remoteChanged()) {
            publish(localDirty = hasUnsyncedChanges(database), remoteChanged = true)
            return SyncResult.BLOCKED
        }
        val file = syncService.push(current.provider, current.binding, database, current.password, rebuildViews, onProgress)
        recordSyncedRevision(file.revisionId)
        markSynced(database)
        return SyncResult.UPLOADED
    }

    /**
     * Downloads the remote into the local working copy and returns the [DbLocation] to switch to. The
     * caller must route this through the database-switch path so the working copy is reopened; the local
     * change baseline is invalidated here and re-captured on the reopened handle (via [markSynced]).
     */
    suspend fun download(onProgress: (SyncProgress) -> Unit = {}): DbLocation {
        val current = session ?: error("download requires an active remote session")
        val result = syncService.hydrate(current.provider, current.binding, current.password, onProgress)
        recordSyncedRevision(result.revisionId)
        // Invalidate the local baseline so AppStartupHost re-captures it on the reopened database handle.
        syncedToken = null
        publish(localDirty = false, remoteChanged = false)
        return result.location
    }

    /**
     * Updates the in-memory + persisted remote-revision baseline after a successful upload/download.
     * A null [revisionId] (provider didn't return one this run) keeps the previous baseline rather than
     * wiping it, so remote-change detection isn't disabled by a missing revision.
     */
    private fun recordSyncedRevision(revisionId: String?) {
        val effective = revisionId ?: syncedRevision ?: return
        syncedRevision = effective
        val current = session ?: return
        val updated = current.binding.copy(syncedRevision = effective)
        session = current.copy(binding = updated)
        syncService.bind(updated)
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
        syncedToken = null
        syncedRevision = null
        _syncState.value = SyncState()
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
