package com.moneymanager.remotestorage.sync

import com.moneymanager.localsettings.LocalSettings

/**
 * The sync baseline for one local vault file: which remote file backs it, the remote revision last synced
 * to (remote-change detection) and the hash of the local encrypted bytes last synced (local-change detection
 * without a network call).
 */
data class CredentialSyncedBaseline(
    val remoteFileId: String,
    val syncedRevision: String?,
    val syncedHash: String,
)

/**
 * Persists the credential backup's own remote-storage connection (independent of any database binding or
 * the strategy library's) plus a baseline per local vault path, in [LocalSettings].
 */
class CredentialRemoteConnectionStore(
    private val localSettings: LocalSettings,
) {
    fun providerId(): String? = localSettings.getString(KEY_PROVIDER_ID)

    fun providerConfig(): String? = localSettings.getString(KEY_PROVIDER_CONFIG)

    fun isConnected(): Boolean = providerId() != null

    fun saveConnection(
        providerId: String,
        config: String?,
    ) {
        localSettings.putString(KEY_PROVIDER_ID, providerId)
        if (config != null) localSettings.putString(KEY_PROVIDER_CONFIG, config) else localSettings.remove(KEY_PROVIDER_CONFIG)
    }

    fun clearConnection() {
        localSettings.remove(KEY_PROVIDER_ID)
        localSettings.remove(KEY_PROVIDER_CONFIG)
    }

    fun baseline(vaultPath: String): CredentialSyncedBaseline? {
        val parts = localSettings.getString(baselineKey(vaultPath))?.split(FIELD_SEP) ?: return null
        if (parts.size != BASELINE_FIELDS) return null
        return CredentialSyncedBaseline(remoteFileId = parts[0], syncedRevision = parts[1].ifEmpty { null }, syncedHash = parts[2])
    }

    fun putBaseline(
        vaultPath: String,
        baseline: CredentialSyncedBaseline,
    ) {
        val fields = listOf(baseline.remoteFileId, baseline.syncedRevision.orEmpty(), baseline.syncedHash)
        // A value that couldn't round-trip is skipped: that only degrades to "never synced", which merges.
        if (fields.any { field -> field.contains(FIELD_SEP) || !field.isXmlSafe() }) return
        localSettings.putString(baselineKey(vaultPath), fields.joinToString(FIELD_SEP))
    }

    // Hashed rather than raw: a file path easily exceeds java.util.prefs' 80-char key limit.
    private fun baselineKey(vaultPath: String): String = "$KEY_BASELINE_PREFIX${stableHash(vaultPath)}"

    private companion object {
        const val KEY_PROVIDER_ID = "credentialSync.providerId"
        const val KEY_PROVIDER_CONFIG = "credentialSync.providerConfig"
        const val KEY_BASELINE_PREFIX = "credentialSync.baseline."

        // '|' cannot appear in Drive file ids/revisions (URL-safe base64) or in the hex hash.
        const val FIELD_SEP = "|"
        const val BASELINE_FIELDS = 3
    }
}
