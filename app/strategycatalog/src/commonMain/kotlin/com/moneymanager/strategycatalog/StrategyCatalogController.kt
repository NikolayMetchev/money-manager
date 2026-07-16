package com.moneymanager.strategycatalog

import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.strategy.CsvResolution
import com.moneymanager.domain.strategy.CsvUnresolvedReference
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyLibrary
import com.moneymanager.domain.strategy.StrategyParseResult
import com.moneymanager.localsettings.KEY_STRATEGY_CATALOG_LOCAL_DIRECTORY
import com.moneymanager.localsettings.LocalSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a catalog artifact stands relative to the local database. */
enum class CatalogItemStatus {
    /** Installed and identical to the catalog's version. */
    INSTALLED,

    /** Not present locally; can be installed. */
    NOT_INSTALLED,

    /** Present locally but differing from the catalog's version (either side may be newer). */
    UPDATE_AVAILABLE,
}

/** One catalog row: the manifest entry plus its status against the local database. */
data class CatalogItem(
    val entry: CatalogEntry,
    val status: CatalogItemStatus,
)

/** Reactive snapshot of the catalog view (drives the catalog screen). */
data class CatalogState(
    val items: List<CatalogItem> = emptyList(),
    val busy: Boolean = false,
    /** Human-readable fetch/install failure; items are kept from the last success so Retry can compare. */
    val error: String? = null,
) {
    val loaded: Boolean get() = items.isNotEmpty()
}

/**
 * Session-scoped coordinator for the central strategy catalog, modelled on `StrategySyncController`
 * but read-only and connectionless: the catalog is a public HTTP site, never written to, and nothing
 * is deleted locally. Classification compares the manifest's canonical content hashes against
 * [StrategyLibrary.listLocal] entries (both sides hash with the same version-blanked FNV-1a), so a
 * refresh costs a single manifest fetch. The per-database [StrategyLibrary] is passed into each call,
 * so one controller serves every open database; installs go through [StrategyLibrary.applyIncoming]
 * (the sole-writer engine underneath).
 *
 * The catalog [source] defaults to the published site ([remoteSource]) but can be pointed at a local
 * directory instead — a developer testing changes to built-in strategies doesn't need to publish them
 * first. The override is persisted in [localSettings] so it survives restarts, and
 * [localDirectorySourceFactory] builds the platform-specific local-directory source (plain
 * `java.io.File`, so JVM and Android both implement it identically).
 */
class StrategyCatalogController(
    private val remoteSource: StrategyCatalogSource,
    private val localDirectorySourceFactory: (String) -> StrategyCatalogSource,
    private val localSettings: LocalSettings,
) {
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    /** Absolute path of the local-directory override, or null when reading from the published site. */
    val localDirectoryOverride: String?
        get() = localSettings.getString(KEY_STRATEGY_CATALOG_LOCAL_DIRECTORY)?.takeIf { it.isNotBlank() }

    private var source: StrategyCatalogSource = localDirectoryOverride?.let(localDirectorySourceFactory) ?: remoteSource

    /**
     * Switches the catalog source and persists the choice; null/blank reverts to the published site.
     * The cached state is cleared since it reflects the previous source — call [refresh] afterward.
     */
    fun setLocalDirectoryOverride(directoryPath: String?) {
        val trimmed = directoryPath?.trim()?.ifBlank { null }
        source =
            if (trimmed == null) {
                localSettings.remove(KEY_STRATEGY_CATALOG_LOCAL_DIRECTORY)
                remoteSource
            } else {
                localSettings.putString(KEY_STRATEGY_CATALOG_LOCAL_DIRECTORY, trimmed)
                localDirectorySourceFactory(trimmed)
            }
        _state.value = CatalogState()
    }

    /** Marks the state busy so the UI can disable actions the instant one is triggered. */
    fun beginBusy() {
        _state.value = _state.value.copy(busy = true)
    }

    /** Fetches the manifest and classifies every entry against the local library. */
    suspend fun refresh(
        library: StrategyLibrary,
        appVersion: AppVersion,
    ) {
        runCatching {
            val manifest = source.fetchManifest()
            val localHashes = library.listLocal(appVersion).associate { it.key to it.contentHash }
            manifest.entries
                .map { entry -> CatalogItem(entry, classify(localHashes[entry.key], entry.contentHash)) }
                .sortedBy { it.entry.name.lowercase() }
        }.onSuccess { items ->
            _state.value = CatalogState(items = items)
        }.onFailure { failure ->
            _state.value = _state.value.copy(busy = false, error = failure.message ?: "Couldn't load the catalog")
        }
    }

    /** Downloads the artifacts for [keys] and reports their unresolved references (install preview). */
    suspend fun previewInstall(
        library: StrategyLibrary,
        keys: Set<StrategyKey>,
    ): List<StrategyParseResult> =
        clearingBusyOnFailure {
            entriesFor(keys).map { entry ->
                library.parseIncoming(entry.key, source.fetchArtifact(entry.fileName))
            }
        }

    /**
     * Installs (create-or-update by name) the artifacts for [keys], applying [resolutions] for any
     * references reported by [previewInstall], then refreshes the view. Returns how many installed.
     */
    suspend fun install(
        library: StrategyLibrary,
        appVersion: AppVersion,
        keys: Set<StrategyKey>,
        resolutions: Map<StrategyKey, Map<CsvUnresolvedReference, CsvResolution>> = emptyMap(),
    ): Int {
        var installed = 0
        var failure: Throwable? = null
        try {
            for (entry in entriesFor(keys)) {
                val json = source.fetchArtifact(entry.fileName)
                library.applyIncoming(entry.key, json, resolutions[entry.key] ?: emptyMap())
                installed++
            }
        } catch (expected: Throwable) {
            failure = expected
        }
        // Refresh even after a partial failure so entries applied before the error show as installed
        // (a successful refresh clears `error`, so republish the failure after it).
        refresh(library, appVersion)
        failure?.let {
            _state.value = _state.value.copy(busy = false, error = it.message ?: "Catalog action failed")
            throw it
        }
        return installed
    }

    private fun entriesFor(keys: Set<StrategyKey>): List<CatalogEntry> =
        _state.value.items
            .map { it.entry }
            .filter { it.key in keys }

    private fun classify(
        localHash: String?,
        catalogHash: String,
    ): CatalogItemStatus =
        when (localHash) {
            null -> CatalogItemStatus.NOT_INSTALLED
            catalogHash -> CatalogItemStatus.INSTALLED
            else -> CatalogItemStatus.UPDATE_AVAILABLE
        }

    // A failure mid-action publishes the error and clears busy, keeping the last good item list so
    // the screen stays usable; the caller's Retry re-runs the action.
    private inline fun <T> clearingBusyOnFailure(block: () -> T): T =
        try {
            block()
        } catch (expected: Throwable) {
            _state.value = _state.value.copy(busy = false, error = expected.message ?: "Catalog action failed")
            throw expected
        }
}
