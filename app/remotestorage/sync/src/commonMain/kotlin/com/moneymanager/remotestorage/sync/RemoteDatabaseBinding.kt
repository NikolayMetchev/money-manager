package com.moneymanager.remotestorage.sync

import com.moneymanager.localsettings.LocalSettings

/**
 * Records that the active database is backed by a remote-storage provider, so the app can restore it
 * automatically on the next launch (issue #86, point 2).
 *
 * @property providerId the [com.moneymanager.remotestorage.RemoteStorageProvider.id] to re-resolve
 * @property remoteFileId the provider-specific id of the uploaded archive
 * @property remoteName the archive's display name on the remote (kept stable across syncs)
 * @property localCachePath where the hydrated working copy lives locally ([com.moneymanager.domain.model.DbLocation] string)
 * @property providerConfig provider-specific reconstruction config (e.g. a folder path); null when unused
 * @property syncedRevision the remote [com.moneymanager.remotestorage.RemoteFile.revisionId] this device last
 *   synced to; persisted so remote-change detection survives app restarts. Null until the first sync.
 * @property syncedToken the [com.moneymanager.database.write.MoneyManagerDatabaseWrapper.dataChangeToken] of the
 *   working copy the last time it was uploaded; persisted so "has the local file changed since the last
 *   upload?" survives app restarts (the token is content-derived and stable across reopen). Null until the
 *   first sync.
 */
data class RemoteDatabaseBinding(
    val providerId: String,
    val remoteFileId: String,
    val remoteName: String,
    val localCachePath: String,
    val providerConfig: String? = null,
    val syncedRevision: String? = null,
    val syncedToken: Long? = null,
)

private const val KEY_PROVIDER_ID = "remoteDb.providerId"
private const val KEY_FILE_ID = "remoteDb.fileId"
private const val KEY_NAME = "remoteDb.name"
private const val KEY_LOCAL_PATH = "remoteDb.localPath"
private const val KEY_PROVIDER_CONFIG = "remoteDb.providerConfig"
private const val KEY_SYNCED_REVISION = "remoteDb.syncedRevision"
private const val KEY_SYNCED_TOKEN = "remoteDb.syncedToken"

/** Persists the [RemoteDatabaseBinding] for the active database in [LocalSettings]. */
class RemoteDatabaseBindingStore(
    private val localSettings: LocalSettings,
) {
    fun load(): RemoteDatabaseBinding? {
        val providerId = localSettings.getString(KEY_PROVIDER_ID) ?: return null
        val fileId = localSettings.getString(KEY_FILE_ID) ?: return null
        val name = localSettings.getString(KEY_NAME) ?: return null
        val localPath = localSettings.getString(KEY_LOCAL_PATH) ?: return null
        return RemoteDatabaseBinding(
            providerId,
            fileId,
            name,
            localPath,
            localSettings.getString(KEY_PROVIDER_CONFIG),
            localSettings.getString(KEY_SYNCED_REVISION),
            localSettings.getString(KEY_SYNCED_TOKEN)?.toLongOrNull(),
        )
    }

    fun save(binding: RemoteDatabaseBinding) {
        localSettings.putString(KEY_PROVIDER_ID, binding.providerId)
        localSettings.putString(KEY_FILE_ID, binding.remoteFileId)
        localSettings.putString(KEY_NAME, binding.remoteName)
        localSettings.putString(KEY_LOCAL_PATH, binding.localCachePath)
        binding.providerConfig?.let { localSettings.putString(KEY_PROVIDER_CONFIG, it) } ?: localSettings.remove(KEY_PROVIDER_CONFIG)
        binding.syncedRevision?.let { localSettings.putString(KEY_SYNCED_REVISION, it) } ?: localSettings.remove(KEY_SYNCED_REVISION)
        binding.syncedToken?.let { localSettings.putString(KEY_SYNCED_TOKEN, it.toString()) } ?: localSettings.remove(KEY_SYNCED_TOKEN)
    }

    fun clear() {
        localSettings.remove(KEY_PROVIDER_ID)
        localSettings.remove(KEY_FILE_ID)
        localSettings.remove(KEY_NAME)
        localSettings.remove(KEY_LOCAL_PATH)
        localSettings.remove(KEY_PROVIDER_CONFIG)
        localSettings.remove(KEY_SYNCED_REVISION)
        localSettings.remove(KEY_SYNCED_TOKEN)
    }
}
