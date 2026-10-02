package com.moneymanager.remotestorage.sync

import com.moneymanager.archive.ArchiveDecryptionException
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.remotestorage.RemoteAuthException
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.reconnect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the credential backup stands, for the Credentials card. */
enum class CredentialSyncStatus {
    /** Not synced this run yet (or the vault is locked, so there is nothing to compare). */
    IDLE,

    /** The local vault file and the backup match. */
    IN_SYNC,

    /** The backup is encrypted under a different password; the user must enter it to merge. */
    NEEDS_REMOTE_PASSWORD,

    /** The last sync failed; see [CredentialSyncState.message]. */
    ERROR,
}

data class CredentialSyncState(
    val status: CredentialSyncStatus = CredentialSyncStatus.IDLE,
    val message: String? = null,
    /** True when the failure was an expired/revoked remote sign-in, so the UI offers to re-consent. */
    val needsReconnect: Boolean = false,
    val busy: Boolean = false,
)

/**
 * Backs the credential vault file up to remote storage (opt-in), on its own connection, like
 * [StrategySyncController]. The vault file is already encrypted, so its bytes are uploaded as they are.
 *
 * Two-way, per vault file, against a [CredentialSyncedBaseline]: a side that changed since the last sync
 * wins; when both changed, or this device has never synced with an existing backup (a new device), the two
 * are merged ([com.moneymanager.credentialvault.CredentialBundle.mergedWith]) and the result uploaded. A backup under a different password
 * needs that password once, after which this device's vault adopts it.
 */
class CredentialSyncController(
    private val providerFactory: RemoteStorageProviderFactory,
    private val store: CredentialRemoteConnectionStore,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(CredentialSyncState())
    val state: StateFlow<CredentialSyncState> = _state.asStateFlow()

    fun isConnected(): Boolean = store.isConnected()

    /** The remote backends offered by this build, for the connect action. */
    fun availableProviders(): List<RemoteStorageType> = providerFactory.types()

    /** Signs in to [providerId] with [config] (interactive if needed) and records the connection. */
    suspend fun connect(
        providerId: String,
        config: String?,
    ) {
        resolveSignedIn(providerId, config)
        store.saveConnection(providerId, config)
    }

    /** Forces fresh consent for the connected provider, recovering a revoked/expired refresh token. */
    suspend fun reconnect() {
        val providerId = store.providerId() ?: error("Credentials are not backed up to remote storage")
        providerFactory.reconnect(providerId, store.providerConfig(), SUBFOLDER)
        _state.value = _state.value.copy(needsReconnect = false)
    }

    /** Stops backing up (the remote copy and the local file are both kept). */
    fun disconnect() {
        store.clearConnection()
        _state.value = CredentialSyncState()
    }

    /**
     * Syncs the unlocked [vault] with its backup. A no-op when not connected or the vault isn't unlocked:
     * this never prompts, so it is safe to run in the background. [remotePassword] answers a previous
     * [CredentialSyncStatus.NEEDS_REMOTE_PASSWORD]. Failures are reported through [state], not thrown.
     */
    suspend fun syncNow(
        vault: CredentialVault,
        remotePassword: String? = null,
    ) {
        if (!isConnected()) return
        mutex.withLock {
            val path = (vault.state.value as? VaultState.Unlocked)?.path ?: return
            _state.value = _state.value.copy(busy = true)
            _state.value =
                try {
                    sync(vault, path, remotePassword)
                    CredentialSyncState(CredentialSyncStatus.IN_SYNC)
                } catch (cancelled: CancellationException) {
                    _state.value = _state.value.copy(busy = false)
                    throw cancelled
                } catch (_: ArchiveDecryptionException) {
                    CredentialSyncState(
                        CredentialSyncStatus.NEEDS_REMOTE_PASSWORD,
                        if (remotePassword == null) null else "That isn't the backup's password",
                    )
                } catch (_: CredentialVaultLockedException) {
                    CredentialSyncState()
                } catch (auth: RemoteAuthException) {
                    CredentialSyncState(CredentialSyncStatus.ERROR, auth.message, needsReconnect = true)
                } catch (expected: Exception) {
                    CredentialSyncState(CredentialSyncStatus.ERROR, expected.message ?: "Backup failed")
                }
        }
    }

    private suspend fun sync(
        vault: CredentialVault,
        path: String,
        remotePassword: String?,
    ) {
        val provider = resolveSignedIn()
        val name = remoteNameFor(path)
        val baseline = store.baseline(path)
        val remote = baseline?.let { provider.stat(it.remoteFileId) } ?: provider.list().firstOrNull { it.name == name }
        val local = vault.encryptedBytes() ?: return
        if (remote == null) {
            upload(provider, null, name, path, local)
            return
        }
        // A baseline for some other remote file (e.g. the old one was deleted) says nothing about this one.
        val known = baseline?.takeIf { it.remoteFileId == remote.id }
        val localChanged = known == null || known.syncedHash != stableHash(local)
        val remoteChanged = known == null || (remote.revisionId != null && remote.revisionId != known.syncedRevision)
        when {
            remoteChanged -> {
                // Passing `local` makes a secret saved during the download merge in rather than be replaced.
                val applied = vault.applyRemote(provider.download(remote.id), remotePassword, merge = localChanged, expectedLocal = local)
                if (applied.merged) {
                    upload(provider, remote.id, name, path, applied.fileBytes)
                } else {
                    saveBaseline(path, remote, applied.fileBytes)
                }
            }
            localChanged -> upload(provider, remote.id, name, path, local)
            else -> Unit
        }
    }

    private suspend fun upload(
        provider: RemoteStorageProvider,
        fileId: String?,
        name: String,
        path: String,
        bytes: ByteArray,
    ) {
        saveBaseline(path, provider.upload(fileId, name, bytes), bytes)
    }

    private fun saveBaseline(
        path: String,
        remote: RemoteFile,
        localBytes: ByteArray,
    ) {
        store.putBaseline(path, CredentialSyncedBaseline(remote.id, remote.revisionId, stableHash(localBytes)))
    }

    private suspend fun resolveSignedIn(): RemoteStorageProvider {
        val providerId = store.providerId() ?: error("Credentials are not backed up to remote storage")
        return resolveSignedIn(providerId, store.providerConfig())
    }

    private suspend fun resolveSignedIn(
        providerId: String,
        config: String?,
    ): RemoteStorageProvider {
        val provider = providerFactory.create(providerId, config, SUBFOLDER)
        if (!provider.isSignedIn()) provider.signIn()
        return provider
    }

    companion object {
        /** The Drive subfolder (under "Money Manager") the credential backups live in. */
        const val SUBFOLDER: String = "Credentials"

        /**
         * The backup is named after the vault file, so vaults with the same name (e.g. the default one next
         * to each database) share one backup — and so one set of credentials across databases and devices.
         */
        fun remoteNameFor(vaultPath: String): String = vaultPath.substringAfterLast('/').substringAfterLast('\\')
    }
}
