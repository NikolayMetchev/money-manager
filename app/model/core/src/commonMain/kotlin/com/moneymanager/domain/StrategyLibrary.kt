package com.moneymanager.domain

import com.moneymanager.domain.model.AppVersion

/**
 * The kinds of strategy artifact stored in the shared library, one file per artifact on the remote.
 * CSV and QIF are both `csv_import_strategy` rows (QIF rides the CSV engine); they are separated here
 * only so the filename suffix records which kind a file is. [GLOBAL_MAPPINGS] is the single combined
 * file holding all global account mappings. [PASS_THROUGH] is one pass-through (conduit) account
 * definition per file.
 */
enum class StrategyKind {
    CSV,
    QIF,
    API,
    GLOBAL_MAPPINGS,
    PASS_THROUGH,
}

/**
 * Identity of a library artifact. For [StrategyKind.GLOBAL_MAPPINGS] the [name] is the fixed
 * [StrategyFileNaming.GLOBAL_MAPPINGS_NAME]; for the others it is the strategy's unique name.
 */
data class StrategyKey(
    val kind: StrategyKind,
    val name: String,
)

/**
 * A local artifact rendered to its portable JSON payload (the exact bytes uploaded to the remote),
 * with a stable [contentHash] used to detect local changes since the last sync without a network call.
 */
data class LocalStrategyEntry(
    val key: StrategyKey,
    val json: String,
    val contentHash: String,
)

/** Unresolved references found while parsing an incoming artifact, to be resolved before applying. */
data class StrategyParseResult(
    val key: StrategyKey,
    val unresolvedReferences: List<CsvUnresolvedReference>,
)

/**
 * Maps library artifacts to/from their remote filenames. The filename suffix encodes the [StrategyKind]
 * so the destination is knowable from the name alone (e.g. `Wise.csv.json`, `Wise.api.json`).
 */
object StrategyFileNaming {
    const val GLOBAL_MAPPINGS_NAME: String = "global-account-mappings"
    private const val SUFFIX = ".json"
    private const val CSV_INFIX = ".csv"
    private const val QIF_INFIX = ".qif"
    private const val API_INFIX = ".api"
    private const val PASS_THROUGH_INFIX = ".passthrough"

    fun fileName(key: StrategyKey): String =
        when (key.kind) {
            StrategyKind.CSV -> "${key.name}$CSV_INFIX$SUFFIX"
            StrategyKind.QIF -> "${key.name}$QIF_INFIX$SUFFIX"
            StrategyKind.API -> "${key.name}$API_INFIX$SUFFIX"
            StrategyKind.GLOBAL_MAPPINGS -> "$GLOBAL_MAPPINGS_NAME$SUFFIX"
            StrategyKind.PASS_THROUGH -> "${key.name}$PASS_THROUGH_INFIX$SUFFIX"
        }

    /** Parses a remote filename back into a [StrategyKey], or null if it isn't a library file. */
    fun parse(fileName: String): StrategyKey? {
        if (!fileName.endsWith(SUFFIX)) return null
        val stem = fileName.removeSuffix(SUFFIX)
        if (stem == GLOBAL_MAPPINGS_NAME) {
            return StrategyKey(StrategyKind.GLOBAL_MAPPINGS, GLOBAL_MAPPINGS_NAME)
        }
        return when {
            stem.endsWith(CSV_INFIX) -> StrategyKey(StrategyKind.CSV, stem.removeSuffix(CSV_INFIX))
            stem.endsWith(QIF_INFIX) -> StrategyKey(StrategyKind.QIF, stem.removeSuffix(QIF_INFIX))
            stem.endsWith(API_INFIX) -> StrategyKey(StrategyKind.API, stem.removeSuffix(API_INFIX))
            stem.endsWith(PASS_THROUGH_INFIX) -> StrategyKey(StrategyKind.PASS_THROUGH, stem.removeSuffix(PASS_THROUGH_INFIX))
            else -> null
        }?.takeIf { it.name.isNotBlank() }
    }
}

/**
 * Reads the local strategy set as portable JSON artifacts and applies incoming artifacts back into the
 * database (create-or-update keyed by name, so a re-import never duplicates). This is the local half of
 * the strategy-sync feature; the remote half (Drive I/O, change detection) lives in the sync module.
 */
interface StrategyLibrary {
    /** Every local CSV/QIF/API strategy plus the global mappings, each as its JSON artifact. */
    suspend fun listLocal(appVersion: AppVersion): List<LocalStrategyEntry>

    /**
     * A stable, version-independent hash of the artifact [json] for [key], used to tell whether two
     * artifacts are semantically equal regardless of the `version` stamp they were exported under (so an
     * app upgrade doesn't spuriously mark every strategy as locally changed). Matches
     * [LocalStrategyEntry.contentHash].
     */
    fun canonicalHash(
        key: StrategyKey,
        json: String,
    ): String

    /** Decodes [json] for [key] and reports any references that don't resolve in this database. */
    suspend fun parseIncoming(
        key: StrategyKey,
        json: String,
    ): StrategyParseResult

    /**
     * Applies the incoming [json] artifact for [key]: creates it, or updates the existing artifact with
     * the same name in place (never a duplicate). [resolutions] resolve any references reported by
     * [parseIncoming]; unresolved references default to creating the missing entity by its exported name.
     */
    suspend fun applyIncoming(
        key: StrategyKey,
        json: String,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    )
}
