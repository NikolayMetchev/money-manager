package com.moneymanager.remotestorage.sync

import com.moneymanager.archive.ArchiveCodec
import com.moneymanager.archive.ArchiveDecryptionException
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.DatabaseMaintenanceServiceImpl
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.dbLocationFromString
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.RemoteStorageProvider

/**
 * Ties together the database layer ([DatabaseManager] + materialized-view maintenance), the
 * shrink/encrypt pipeline ([ArchiveCodec]) and a [RemoteStorageProvider] to back the active database
 * with remote storage.
 *
 * - **createRemote / push** shrink the database (truncate materialized views), snapshot it to a
 *   compact WAL-free file, compress + encrypt it, and upload it.
 * - **hydrate** downloads, decrypts, decompresses and writes the working copy back to its local path.
 *
 * The live database's materialized views are rebuilt after every upload so the running app keeps
 * working; only the uploaded snapshot is shrunk.
 */
class RemoteDatabaseSyncService(
    private val databaseManager: DatabaseManager,
    localSettings: LocalSettings,
) {
    private val bindingStore = RemoteDatabaseBindingStore(localSettings)

    fun activeBinding(): RemoteDatabaseBinding? = bindingStore.load()

    fun clearBinding() = bindingStore.clear()

    /**
     * Uploads [database] as a new remote archive named [remoteName], persists the resulting binding
     * (so the database is remote-backed from now on) and returns it.
     */
    suspend fun createRemote(
        provider: RemoteStorageProvider,
        remoteName: String,
        localCachePath: DbLocation,
        database: MoneyManagerDatabaseWrapper,
        password: String,
        providerConfig: String? = null,
    ): RemoteDatabaseBinding {
        val archive = shrinkAndPack(database, password)
        val file = provider.upload(fileId = null, name = remoteName, bytes = archive)
        val binding = RemoteDatabaseBinding(provider.id, file.id, remoteName, localCachePath.toString(), providerConfig)
        bindingStore.save(binding)
        return binding
    }

    /** Persists [binding] as the active remote-backed database (used after an open-from-remote flow). */
    fun bind(binding: RemoteDatabaseBinding) = bindingStore.save(binding)

    /** On-disk size of the working copy at [location] (incl. WAL/SHM), or null if absent. */
    suspend fun localDatabaseSize(location: DbLocation): Long? = databaseManager.databaseSizeBytes(location)

    /**
     * Returns true if [password] correctly decrypts the remote archive for [binding] (downloads and
     * attempts to unpack it, without touching the local copy). Used to safely resume a session before
     * pushing, so a mistyped password can't silently re-encrypt the remote with the wrong key.
     */
    suspend fun verify(
        provider: RemoteStorageProvider,
        binding: RemoteDatabaseBinding,
        password: String,
    ): Boolean =
        try {
            ArchiveCodec.unpack(provider.download(binding.remoteFileId), password)
            true
        } catch (expected: ArchiveDecryptionException) {
            false
        }

    /** Re-uploads [database] to the remote file recorded in [binding] (last-writer-wins). */
    suspend fun push(
        provider: RemoteStorageProvider,
        binding: RemoteDatabaseBinding,
        database: MoneyManagerDatabaseWrapper,
        password: String,
    ) {
        val archive = shrinkAndPack(database, password)
        provider.upload(fileId = binding.remoteFileId, name = binding.remoteName, bytes = archive)
    }

    /**
     * Downloads and rehydrates the bound database into its local cache path, returning the
     * [DbLocation] to open. After opening, callers should rebuild materialized views with
     * [com.moneymanager.database.DatabaseMaintenanceService.fullRefreshMaterializedViews].
     */
    suspend fun hydrate(
        provider: RemoteStorageProvider,
        binding: RemoteDatabaseBinding,
        password: String,
    ): DbLocation {
        val archive = provider.download(binding.remoteFileId)
        val databaseBytes = ArchiveCodec.unpack(archive, password)
        val location = dbLocationFromString(binding.localCachePath)
        databaseManager.restore(location, databaseBytes)
        // The uploaded snapshot has its materialized views truncated; rebuild them so the working
        // copy is consistent before the app opens it.
        val opened = databaseManager.openDatabase(location)
        try {
            DatabaseMaintenanceServiceImpl(opened).fullRefreshMaterializedViews()
        } finally {
            opened.close()
        }
        return location
    }

    /**
     * Truncates the (derived) materialized views, exports a compact snapshot, then rebuilds the views
     * so the live database stays usable. Returns the encrypted archive bytes.
     */
    private suspend fun shrinkAndPack(database: MoneyManagerDatabaseWrapper, password: String): ByteArray {
        val maintenance = DatabaseMaintenanceServiceImpl(database)
        maintenance.truncateMaterializedViews()
        val snapshot = databaseManager.snapshot(database)
        maintenance.fullRefreshMaterializedViews()
        return ArchiveCodec.pack(snapshot, password)
    }
}
