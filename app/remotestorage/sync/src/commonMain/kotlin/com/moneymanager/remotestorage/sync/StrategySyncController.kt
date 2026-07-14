package com.moneymanager.remotestorage.sync

import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.strategy.CsvResolution
import com.moneymanager.domain.strategy.CsvUnresolvedReference
import com.moneymanager.domain.strategy.LocalStrategyEntry
import com.moneymanager.domain.strategy.StrategyFileNaming
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyLibrary
import com.moneymanager.domain.strategy.StrategyParseResult
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.reconnect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a single artifact stands relative to the remote library. */
enum class StrategyItemStatus {
    /** Present and identical on both sides. */
    IN_SYNC,

    /** Local-only; will be uploaded on the next sync. */
    NEW_LOCAL,

    /** Changed locally since the last sync; will be uploaded. */
    LOCAL_AHEAD,

    /** Changed on the remote since the last sync; can be pulled. */
    REMOTE_AHEAD,

    /** Changed on both sides since the last sync; the user picks a winner. */
    CONFLICT,

    /** Remote-only; can be imported (selective). */
    AVAILABLE,
}

/** One artifact row in the strategy-library view. */
data class StrategyItem(
    val key: StrategyKey,
    val status: StrategyItemStatus,
)

/** Reactive snapshot of the whole library relative to the remote (drives the settings card). */
data class StrategyLibraryState(
    val connected: Boolean = false,
    val items: List<StrategyItem> = emptyList(),
    val busy: Boolean = false,
) {
    val toUpload: Int get() = items.count { it.status == StrategyItemStatus.NEW_LOCAL || it.status == StrategyItemStatus.LOCAL_AHEAD }
    val updatesAvailable: Int get() = items.count { it.status == StrategyItemStatus.REMOTE_AHEAD }
    val newOnRemote: Int get() = items.count { it.status == StrategyItemStatus.AVAILABLE }
    val conflicts: Int get() = items.count { it.status == StrategyItemStatus.CONFLICT }
}

/** Result of a [StrategySyncController.syncNow] run. */
data class StrategySyncSummary(
    val uploaded: Int,
    val pulled: Int,
)

/**
 * Session-scoped coordinator for the shared strategy library on remote storage, modelled on
 * [RemoteDatabaseController] but independent of any database binding: strategies are stored one file per
 * artifact (see [StrategyFileNaming]) in the [SUBFOLDER] namespace, uploads are automatic and downloads
 * are selective, and nothing is ever deleted across sides (keep-forever library).
 *
 * The local artifact set comes from a per-database [StrategyLibrary] passed into each call (like how
 * [RemoteDatabaseController] takes the active database), so one controller serves every open database.
 */
class StrategySyncController(
    private val providerFactory: RemoteStorageProviderFactory,
    private val store: StrategyRemoteConnectionStore,
) {
    private val _state = MutableStateFlow(StrategyLibraryState(connected = store.isConnected()))
    val state: StateFlow<StrategyLibraryState> = _state.asStateFlow()

    fun isConnected(): Boolean = store.isConnected()

    /** The remote backends offered by this build, for the connect action. */
    fun availableProviders(): List<RemoteStorageType> = providerFactory.types()

    /** Signs in to [providerId] with [config] (interactive if needed) and records the connection. */
    suspend fun connect(
        providerId: String,
        config: String?,
    ) = clearingBusyOnFailure {
        resolveSignedIn(providerId, config)
        store.saveConnection(providerId, config)
        _state.value = _state.value.copy(connected = true)
    }

    /**
     * Forces fresh interactive consent for the connected provider, minting a new refresh token. Recovers
     * a revoked/expired refresh token (Google `invalid_grant`), which the normal [resolveSignedIn] path
     * can't: its `isSignedIn()` check still reports a stored-but-revoked token as signed in and so never
     * re-consents. Mirrors [RemoteDatabaseController.reconnect] via the shared [reconnect] helper.
     */
    suspend fun reconnect() {
        val providerId = store.providerId() ?: error("Strategy library is not connected to remote storage")
        providerFactory.reconnect(providerId, store.providerConfig(), SUBFOLDER)
    }

    /** Disconnects the library from remote storage (keeps local strategies and baselines). */
    fun disconnect() {
        store.clearConnection()
        _state.value = StrategyLibraryState()
    }

    /** Marks the state busy so the UI can disable actions the instant one is triggered. */
    fun beginBusy() {
        _state.value = _state.value.copy(busy = true)
    }

    /**
     * Runs [block], clearing the [StrategyLibraryState.busy] flag before rethrowing on failure. The
     * busy flag set by [beginBusy] is normally cleared by the wholesale state assignment at the end of
     * [refresh]; a network/auth failure would otherwise skip that and leave the UI's actions disabled
     * until restart.
     */
    private inline fun <T> clearingBusyOnFailure(block: () -> T): T =
        try {
            block()
        } catch (expected: Throwable) {
            _state.value = _state.value.copy(busy = false)
            throw expected
        }

    /**
     * Recomputes the library view: cross-references the local artifacts (from [library]) against the
     * remote files and the persisted baselines. Performs network I/O (a remote `list`, plus a content
     * fetch only for never-synced artifacts present on both sides, to tell equal from conflicting).
     */
    suspend fun refresh(
        library: StrategyLibrary,
        appVersion: AppVersion,
    ) = clearingBusyOnFailure {
        if (!isConnected()) {
            _state.value = StrategyLibraryState()
            return@clearingBusyOnFailure
        }
        val provider = resolveSignedIn()
        val remote = remoteByKey(provider)
        val local = library.listLocal(appVersion).associateBy { it.key }
        val items =
            (local.keys + remote.keys).map { key ->
                StrategyItem(key, classify(provider, library, key, local[key]?.contentHash, remote[key]))
            }
        _state.value = StrategyLibraryState(connected = true, items = items.sortedBy { it.key.name.lowercase() })
    }

    /**
     * Two-way sync: uploads every new/locally-changed artifact automatically; pulls the artifacts named
     * in [selectedToPull] (applying [resolutions] for their references); and force-uploads the conflicts
     * named in [forceUpload] (local wins). Nothing is deleted. Republishes the state and returns a summary.
     * [onProgress] is invoked before each per-artifact step so the UI can show a determinate bar.
     */
    @Suppress("LongParameterList")
    suspend fun syncNow(
        library: StrategyLibrary,
        appVersion: AppVersion,
        selectedToPull: Set<StrategyKey> = emptySet(),
        forceUpload: Set<StrategyKey> = emptySet(),
        resolutions: Map<StrategyKey, Map<CsvUnresolvedReference, CsvResolution>> = emptyMap(),
        onProgress: (SyncProgress) -> Unit = {},
    ): StrategySyncSummary =
        clearingBusyOnFailure {
            if (!isConnected()) return@clearingBusyOnFailure StrategySyncSummary(0, 0)
            onProgress(SyncProgress("Checking the remote library…", 0f))
            val provider = resolveSignedIn()
            var remote = remoteByKey(provider)
            val local = library.listLocal(appVersion).associateBy { it.key }

            // One step per local artifact reconciled + per selected pull + the final view refresh.
            val totalSteps = local.size + selectedToPull.size + 1
            var completedSteps = 0

            fun step(message: String) {
                onProgress(SyncProgress(message, completedSteps.toFloat() / totalSteps))
                completedSteps++
            }

            var uploaded = 0
            for ((_, entry) in local) {
                step("Syncing ${entry.key.name}…")
                if (reconcileUpload(provider, library, entry, remote[entry.key], forceUpload)) uploaded++
            }

            var pulled = 0
            if (selectedToPull.isNotEmpty()) {
                remote = remoteByKey(provider) // refresh ids/revisions after uploads
                for (key in selectedToPull) {
                    step("Importing ${key.name}…")
                    val remoteFile = remote[key] ?: continue
                    val json = provider.download(remoteFile.id).decodeToString()
                    library.applyIncoming(key, json, resolutions[key] ?: emptyMap())
                    store.putBaseline(key, StrategySyncedBaseline(remoteFile.id, remoteFile.revisionId, library.canonicalHash(key, json)))
                    pulled++
                }
            }

            step("Refreshing the library view…")
            refresh(library, appVersion)
            onProgress(SyncProgress("Done", 1f))
            StrategySyncSummary(uploaded, pulled)
        }

    /** Downloads the remote artifact [key] and reports its unresolved references (for a pull preview). */
    suspend fun previewPull(
        library: StrategyLibrary,
        key: StrategyKey,
    ): StrategyParseResult {
        val provider = resolveSignedIn()
        val remoteFile = remoteByKey(provider)[key] ?: return StrategyParseResult(key, emptyList())
        val json = provider.download(remoteFile.id).decodeToString()
        return library.parseIncoming(key, json)
    }

    private suspend fun classify(
        provider: RemoteStorageProvider,
        library: StrategyLibrary,
        key: StrategyKey,
        localHash: String?,
        remoteFile: RemoteFile?,
    ): StrategyItemStatus {
        // A key comes from the local set, the remote set, or both, so at least one side is present.
        if (remoteFile == null) return StrategyItemStatus.NEW_LOCAL
        if (localHash == null) return StrategyItemStatus.AVAILABLE

        val baseline = store.baseline(key)
        if (baseline == null) {
            // Never synced but present both sides: compare content to tell equal from conflicting.
            val remoteHash = library.canonicalHash(key, provider.download(remoteFile.id).decodeToString())
            return if (remoteHash == localHash) StrategyItemStatus.IN_SYNC else StrategyItemStatus.CONFLICT
        }
        val localChanged = localHash != baseline.syncedHash
        val remoteChanged = remoteEffectivelyChanged(provider, library, key, remoteFile, baseline)
        return when {
            !localChanged && !remoteChanged -> StrategyItemStatus.IN_SYNC
            localChanged && !remoteChanged -> StrategyItemStatus.LOCAL_AHEAD
            !localChanged && remoteChanged -> StrategyItemStatus.REMOTE_AHEAD
            else -> StrategyItemStatus.CONFLICT
        }
    }

    /**
     * Reconciles one local artifact against its remote counterpart, uploading when appropriate, and
     * returns whether it uploaded. New local → create; local-ahead → update; conflict → only when the
     * key is in [forceUpload]; never-synced-but-equal → adopt the baseline without uploading.
     */
    private suspend fun reconcileUpload(
        provider: RemoteStorageProvider,
        library: StrategyLibrary,
        entry: LocalStrategyEntry,
        remoteFile: RemoteFile?,
        forceUpload: Set<StrategyKey>,
    ): Boolean {
        if (remoteFile == null) {
            upload(provider, entry, remoteFileId = null)
            return true
        }
        val baseline = store.baseline(entry.key)
        if (baseline == null) {
            // Present both sides but never synced (e.g. identical built-ins on first connect): compare
            // content. Equal → adopt the baseline; different → a conflict, upload only if forced.
            val remoteHash = library.canonicalHash(entry.key, provider.download(remoteFile.id).decodeToString())
            return when {
                remoteHash == entry.contentHash -> {
                    store.putBaseline(entry.key, StrategySyncedBaseline(remoteFile.id, remoteFile.revisionId, entry.contentHash))
                    false
                }
                entry.key in forceUpload -> {
                    upload(provider, entry, remoteFile.id)
                    true
                }
                else -> false
            }
        }
        val localChanged = entry.contentHash != baseline.syncedHash
        val remoteChanged = remoteEffectivelyChanged(provider, library, entry.key, remoteFile, baseline)
        // Auto-upload local-ahead; a conflict (both changed) only uploads when the user forced it.
        if (localChanged && (!remoteChanged || entry.key in forceUpload)) {
            upload(provider, entry, remoteFile.id)
            return true
        }
        return false
    }

    private suspend fun upload(
        provider: RemoteStorageProvider,
        entry: LocalStrategyEntry,
        remoteFileId: String?,
    ) {
        val saved = provider.upload(remoteFileId, StrategyFileNaming.fileName(entry.key), entry.json.encodeToByteArray())
        // entry.contentHash is already the canonical (version-independent) hash of these exact bytes.
        store.putBaseline(entry.key, StrategySyncedBaseline(saved.id, saved.revisionId, entry.contentHash))
    }

    // A remote file has advanced past the baseline only when it exposes a revision that differs from the
    // one last synced. A backend without revisions can't signal remote change, so treat it as unchanged.
    private fun remoteAdvanced(
        remoteFile: RemoteFile,
        baseline: StrategySyncedBaseline,
    ): Boolean = remoteFile.revisionId != null && remoteFile.revisionId != baseline.syncedRevision

    /**
     * Whether the remote artifact really changed since the baseline. The revision id is only a hint —
     * a backend may report a different revision without a content change (e.g. Drive's headRevisionId
     * drifting after an upload). When the revision differs, verify by content: if the remote's canonical
     * hash still equals the synced hash, adopt the new revision as the baseline and report "unchanged",
     * so a local-only edit stays LOCAL_AHEAD instead of degrading into a false CONFLICT.
     */
    private suspend fun remoteEffectivelyChanged(
        provider: RemoteStorageProvider,
        library: StrategyLibrary,
        key: StrategyKey,
        remoteFile: RemoteFile,
        baseline: StrategySyncedBaseline,
    ): Boolean {
        if (!remoteAdvanced(remoteFile, baseline)) return false
        val remoteHash = library.canonicalHash(key, provider.download(remoteFile.id).decodeToString())
        if (remoteHash != baseline.syncedHash) return true
        store.putBaseline(key, baseline.copy(remoteFileId = remoteFile.id, syncedRevision = remoteFile.revisionId))
        return false
    }

    private suspend fun remoteByKey(provider: RemoteStorageProvider): Map<StrategyKey, RemoteFile> =
        provider
            .list()
            .mapNotNull { file -> StrategyFileNaming.parse(file.name)?.let { it to file } }
            .toMap()

    private suspend fun resolveSignedIn(): RemoteStorageProvider {
        val providerId = store.providerId() ?: error("Strategy library is not connected to remote storage")
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
        /** The Drive subfolder (under "Money Manager") the strategy library lives in. */
        const val SUBFOLDER: String = "Strategies"
    }
}
