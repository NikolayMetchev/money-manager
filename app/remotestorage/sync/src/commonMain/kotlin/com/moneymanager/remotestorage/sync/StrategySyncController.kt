package com.moneymanager.remotestorage.sync

import co.touchlab.kermit.Logger
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
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val logger = Logger.withTag("StrategySyncController")

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

    /**
     * The remote file can't be read (malformed, or written by a newer app); see [StrategyItem.error].
     * It is neither pulled nor overwritten until the file changes.
     */
    UNREADABLE,
}

/** One artifact row in the strategy-library view; [error] says why an [StrategyItemStatus.UNREADABLE] one failed. */
data class StrategyItem(
    val key: StrategyKey,
    val status: StrategyItemStatus,
    val error: String? = null,
    /** Whether this database has its own copy, which can replace a Drive copy it can't read. */
    val hasLocal: Boolean = false,
)

/** Reactive snapshot of the whole library relative to the remote (drives the settings card). */
data class StrategyLibraryState(
    val items: List<StrategyItem> = emptyList(),
    val busy: Boolean = false,
) {
    val toUpload: Int get() = items.count { it.status == StrategyItemStatus.NEW_LOCAL || it.status == StrategyItemStatus.LOCAL_AHEAD }
    val updatesAvailable: Int get() = items.count { it.status == StrategyItemStatus.REMOTE_AHEAD }
    val newOnRemote: Int get() = items.count { it.status == StrategyItemStatus.AVAILABLE }
    val conflicts: Int get() = items.count { it.status == StrategyItemStatus.CONFLICT }
    val unreadable: Int get() = items.count { it.status == StrategyItemStatus.UNREADABLE }
}

/** Result of a [StrategySyncController.syncNow] run; [failed] counts the selected pulls that couldn't be read or applied. */
data class StrategySyncSummary(
    val uploaded: Int,
    val pulled: Int,
    val failed: Int = 0,
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
    private val _state = MutableStateFlow(StrategyLibraryState())
    val state: StateFlow<StrategyLibraryState> = _state.asStateFlow()

    // Remote files that failed to read or apply, by the revision that failed, so the row keeps its error
    // across refreshes until the file changes. In memory only: a restart simply tries again.
    private val readFailures = mutableMapOf<StrategyKey, ReadFailure>()

    private data class ReadFailure(
        val revisionId: String?,
        val message: String,
    )

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
        readFailures.clear()
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
        val contents = prefetchComparisons(provider, local.keys, remote)
        val items =
            (local.keys + remote.keys).map { key ->
                val status = classify(contents, library, key, local[key]?.contentHash, remote[key])
                StrategyItem(
                    key,
                    status,
                    error = readFailures[key]?.message?.takeIf { status == StrategyItemStatus.UNREADABLE },
                    hasLocal = key in local,
                )
            }
        _state.value = StrategyLibraryState(items = items.sortedBy { it.key.name.lowercase() })
    }

    /**
     * Two-way sync: uploads every new/locally-changed artifact automatically; pulls the artifacts named
     * in [selectedToPull] (applying [resolutions] for their references); and force-uploads the conflicts
     * and unreadable Drive copies named in [forceUpload] (local wins). Nothing is deleted. Republishes the state and returns a summary.
     * [onProgress] is invoked before each per-artifact step so the UI can show a determinate bar.
     */
    @Suppress("LongParameterList")
    suspend fun syncNow(
        library: StrategyLibrary,
        appVersion: AppVersion,
        selectedToPull: Set<StrategyKey> = emptySet(),
        forceUpload: Set<StrategyKey> = emptySet(),
        resolutions: Map<StrategyKey, Map<CsvUnresolvedReference, CsvResolution>> = emptyMap(),
        onProgress: (Progress) -> Unit = {},
    ): StrategySyncSummary =
        clearingBusyOnFailure {
            if (!isConnected()) return@clearingBusyOnFailure StrategySyncSummary(0, 0)
            onProgress(Progress("Checking the remote library…", 0f))
            val provider = resolveSignedIn()
            var remote = remoteByKey(provider)
            val local = library.listLocal(appVersion).associateBy { it.key }

            // One step per local artifact reconciled + per selected pull + the final view refresh.
            val totalSteps = local.size + selectedToPull.size + 1
            var completedSteps = 0

            fun step(message: String) {
                onProgress(Progress(message, completedSteps.toFloat() / totalSteps))
                completedSteps++
            }

            val contents = prefetchComparisons(provider, local.keys, remote)
            var uploaded = 0
            for ((_, entry) in local) {
                step("Syncing ${entry.key.name}…")
                if (reconcileUpload(provider, contents, library, entry, remote[entry.key], forceUpload)) uploaded++
            }

            var pulled = 0
            var failed = 0
            if (selectedToPull.isNotEmpty()) {
                remote = remoteByKey(provider) // refresh ids/revisions after uploads
                val pulls = prefetch(provider, selectedToPull.mapNotNull { remote[it] })
                for (key in selectedToPull) {
                    step("Importing ${key.name}…")
                    val remoteFile = remote[key] ?: continue
                    val json = pulls.get(remoteFile)
                    // One bad file mustn't stop the rest: record it against its row and carry on.
                    val applied =
                        reading(key, remoteFile) {
                            library.applyIncoming(key, json, resolutions[key] ?: emptyMap())
                            library.canonicalHash(key, json)
                        }
                    if (applied == null) {
                        failed++
                    } else {
                        store.putBaseline(key, StrategySyncedBaseline(remoteFile.id, remoteFile.revisionId, applied))
                        pulled++
                    }
                }
            }

            step("Refreshing the library view…")
            refresh(library, appVersion)
            onProgress(Progress("Done", 1f))
            StrategySyncSummary(uploaded, pulled, failed)
        }

    /**
     * Downloads the remote artifact [key] and reports its unresolved references (for a pull preview), or
     * null when the file can't be read: its row is then marked [StrategyItemStatus.UNREADABLE] with the
     * reason, and the caller should leave it out of the pull.
     */
    suspend fun previewPull(
        library: StrategyLibrary,
        key: StrategyKey,
    ): StrategyParseResult? {
        val provider = resolveSignedIn()
        val remoteFile = remoteByKey(provider)[key] ?: return StrategyParseResult(key, emptyList())
        val json = provider.download(remoteFile.id).decodeToString()
        val result = reading(key, remoteFile) { library.parseIncoming(key, json) }
        if (result == null) {
            val failure = readFailures.getValue(key)
            _state.value =
                _state.value.copy(
                    items =
                        _state.value.items.map {
                            if (it.key == key) it.copy(status = StrategyItemStatus.UNREADABLE, error = failure.message) else it
                        },
                )
        }
        return result
    }

    /**
     * Runs [block], which reads or applies the remote file [remoteFile] for [key], and returns its result,
     * or null after recording why it failed. Only the parse/apply step is guarded: a download or sign-in
     * failure is the connection's problem, not this file's, so it still fails the whole operation.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever a malformed file makes the decoders throw.
    private inline fun <T> reading(
        key: StrategyKey,
        remoteFile: RemoteFile,
        block: () -> T,
    ): T? =
        try {
            block().also { readFailures.remove(key) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val message = failure.message ?: failure::class.simpleName ?: "Unknown error"
            logger.w(failure) { "Can't read the remote strategy ${key.kind} \"${key.name}\": $message" }
            readFailures[key] = ReadFailure(remoteFile.revisionId, message)
            null
        }

    /** The canonical hash of [remoteFile], or null when it can't be read (see [reading]). */
    private suspend fun remoteHash(
        contents: RemoteContents,
        library: StrategyLibrary,
        key: StrategyKey,
        remoteFile: RemoteFile,
    ): String? {
        val json = contents.get(remoteFile)
        return reading(key, remoteFile) { library.canonicalHash(key, json) }
    }

    /**
     * Whether [remoteFile] is the revision that already failed to read, so it isn't retried until it changes. A
     * backend without revisions can't say whether it changed, so its files are read again every time.
     */
    private fun knownUnreadable(
        key: StrategyKey,
        remoteFile: RemoteFile,
    ): Boolean =
        remoteFile.revisionId != null &&
            readFailures[key]?.let { it.revisionId == remoteFile.revisionId } == true

    private suspend fun classify(
        contents: RemoteContents,
        library: StrategyLibrary,
        key: StrategyKey,
        localHash: String?,
        remoteFile: RemoteFile?,
    ): StrategyItemStatus {
        // A key comes from the local set, the remote set, or both, so at least one side is present.
        if (remoteFile == null) return StrategyItemStatus.NEW_LOCAL
        if (knownUnreadable(key, remoteFile)) return StrategyItemStatus.UNREADABLE
        if (localHash == null) return StrategyItemStatus.AVAILABLE

        val baseline = store.baseline(key)
        if (baseline == null) {
            // Never synced but present both sides: compare content to tell equal from conflicting.
            val remoteHash = remoteHash(contents, library, key, remoteFile) ?: return StrategyItemStatus.UNREADABLE
            return if (remoteHash == localHash) StrategyItemStatus.IN_SYNC else StrategyItemStatus.CONFLICT
        }
        val localChanged = localHash != baseline.syncedHash
        val remoteChanged =
            remoteEffectivelyChanged(contents, library, key, remoteFile, baseline) ?: return StrategyItemStatus.UNREADABLE
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
    @Suppress("LongParameterList")
    private suspend fun reconcileUpload(
        provider: RemoteStorageProvider,
        contents: RemoteContents,
        library: StrategyLibrary,
        entry: LocalStrategyEntry,
        remoteFile: RemoteFile?,
        forceUpload: Set<StrategyKey>,
    ): Boolean {
        if (remoteFile == null) {
            upload(provider, entry, remoteFileId = null)
            return true
        }
        // A file this app can't read may be a newer app's work, or an older one's in a retired format, so it is
        // only overwritten when the person chose to replace it.
        if (knownUnreadable(entry.key, remoteFile)) {
            if (entry.key !in forceUpload) return false
            upload(provider, entry, remoteFile.id)
            readFailures.remove(entry.key)
            return true
        }
        val baseline = store.baseline(entry.key)
        if (baseline == null) {
            // Present both sides but never synced (e.g. identical built-ins on first connect): compare
            // content. Equal → adopt the baseline; different → a conflict, upload only if forced.
            val remoteHash = remoteHash(contents, library, entry.key, remoteFile) ?: return false
            return when {
                remoteHash == entry.contentHash -> {
                    store.putBaseline(entry.key, StrategySyncedBaseline(remoteFile.id, remoteFile.revisionId, entry.contentHash))
                    false
                }

                entry.key in forceUpload -> {
                    upload(provider, entry, remoteFile.id)
                    true
                }

                else -> {
                    false
                }
            }
        }
        val localChanged = entry.contentHash != baseline.syncedHash
        val remoteChanged = remoteEffectivelyChanged(contents, library, entry.key, remoteFile, baseline) ?: return false
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
     * so a local-only edit stays LOCAL_AHEAD instead of degrading into a false CONFLICT. Null when the
     * changed remote can't be read.
     */
    private suspend fun remoteEffectivelyChanged(
        contents: RemoteContents,
        library: StrategyLibrary,
        key: StrategyKey,
        remoteFile: RemoteFile,
        baseline: StrategySyncedBaseline,
    ): Boolean? {
        if (!remoteAdvanced(remoteFile, baseline)) return false
        val remoteHash = remoteHash(contents, library, key, remoteFile) ?: return null
        if (remoteHash != baseline.syncedHash) return true
        store.putBaseline(key, baseline.copy(remoteFileId = remoteFile.id, syncedRevision = remoteFile.revisionId))
        return false
    }

    /**
     * Fetches, concurrently, every remote artifact the classification below will compare by content: one
     * present on both sides that was never synced, or whose revision moved past its baseline.
     */
    private suspend fun prefetchComparisons(
        provider: RemoteStorageProvider,
        localKeys: Set<StrategyKey>,
        remote: Map<StrategyKey, RemoteFile>,
    ): RemoteContents =
        prefetch(
            provider,
            localKeys.mapNotNull { key ->
                val remoteFile = remote[key] ?: return@mapNotNull null
                val baseline = store.baseline(key)
                remoteFile.takeIf { baseline == null || remoteAdvanced(it, baseline) }
            },
        )

    private suspend fun prefetch(
        provider: RemoteStorageProvider,
        files: Collection<RemoteFile>,
    ): RemoteContents {
        // Downloads suspend rather than block (Ktor), so Default is enough to run them all at once, and
        // common code can't name Dispatchers.IO on native.
        val bodies =
            coroutineScope {
                files
                    .map { file -> async(Dispatchers.Default) { file.id to provider.download(file.id).decodeToString() } }
                    .awaitAll()
                    .toMap()
            }
        return RemoteContents(provider, bodies)
    }

    /** Remote artifact bodies fetched up front; anything not prefetched is downloaded on demand. */
    private class RemoteContents(
        private val provider: RemoteStorageProvider,
        private val prefetched: Map<String, String>,
    ) {
        suspend fun get(file: RemoteFile): String = prefetched[file.id] ?: provider.download(file.id).decodeToString()
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
