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
 */
data class RemoteDatabaseBinding(
    val providerId: String,
    val remoteFileId: String,
    val remoteName: String,
    val localCachePath: String,
    val providerConfig: String? = null,
)

private const val KEY_PROVIDER_ID = "remoteDb.providerId"
private const val KEY_FILE_ID = "remoteDb.fileId"
private const val KEY_NAME = "remoteDb.name"
private const val KEY_LOCAL_PATH = "remoteDb.localPath"
private const val KEY_PROVIDER_CONFIG = "remoteDb.providerConfig"

/** Persists the [RemoteDatabaseBinding] for the active database in [LocalSettings]. */
class RemoteDatabaseBindingStore(
    private val localSettings: LocalSettings,
) {
    fun load(): RemoteDatabaseBinding? {
        val providerId = localSettings.getString(KEY_PROVIDER_ID) ?: return null
        val fileId = localSettings.getString(KEY_FILE_ID) ?: return null
        val name = localSettings.getString(KEY_NAME) ?: return null
        val localPath = localSettings.getString(KEY_LOCAL_PATH) ?: return null
        return RemoteDatabaseBinding(providerId, fileId, name, localPath, localSettings.getString(KEY_PROVIDER_CONFIG))
    }

    fun save(binding: RemoteDatabaseBinding) {
        localSettings.putString(KEY_PROVIDER_ID, binding.providerId)
        localSettings.putString(KEY_FILE_ID, binding.remoteFileId)
        localSettings.putString(KEY_NAME, binding.remoteName)
        localSettings.putString(KEY_LOCAL_PATH, binding.localCachePath)
        binding.providerConfig?.let { localSettings.putString(KEY_PROVIDER_CONFIG, it) } ?: localSettings.remove(KEY_PROVIDER_CONFIG)
    }

    fun clear() {
        localSettings.remove(KEY_PROVIDER_ID)
        localSettings.remove(KEY_FILE_ID)
        localSettings.remove(KEY_NAME)
        localSettings.remove(KEY_LOCAL_PATH)
        localSettings.remove(KEY_PROVIDER_CONFIG)
    }
}
