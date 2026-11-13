package com.moneymanager.domain.strategy

import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.documentKind
import com.moneymanager.domain.model.csvstrategy.isPdfStrategy
import com.moneymanager.domain.model.csvstrategy.isXlsxStrategy

/**
 * The kinds of strategy artifact stored in the shared library, one file per artifact on the remote.
 * CSV, QIF, OFX, XLSX and PDF are all `csv_import_strategy` rows (the others ride the CSV engine); they
 * are separated here only so the filename suffix records which kind a file is ([libraryKind]). [GLOBAL_MAPPINGS] is
 * the single combined file holding all global account mappings. [PASS_THROUGH] is one pass-through
 * (conduit) account definition per file. [CURRENCIES] is the reference list of currencies a database is
 * seeded with (one file, [StrategyFileNaming.CURRENCIES_NAME]); applying it only adds missing codes.
 * [HOLIDAY_CALENDAR] is one location (holiday calendar) per file, named by its code; applying it replaces that
 * location. [ASSETS] is the single file holding a database's own assets and aliases ([StrategyFileNaming.ASSETS_NAME]);
 * like the global mappings it is personal, and applying it only adds.
 * [CRYPTO_ASSETS] is the published crypto list and its aliases ([StrategyFileNaming.CRYPTO_ASSETS_NAME]), rendered from
 * the crypto catalog; applying it only adds, like the currency list.
 * location.
 */
enum class StrategyKind {
    CSV,
    QIF,
    OFX,
    XLSX,
    PDF,
    API,
    GLOBAL_MAPPINGS,
    PASS_THROUGH,
    CURRENCIES,
    HOLIDAY_CALENDAR,
    ASSETS,
    CRYPTO_ASSETS,
}

/** Which of the CSV engine's kinds this strategy's library file is. */
fun CsvImportStrategy.libraryKind(): StrategyKind =
    when {
        isPdfStrategy() -> StrategyKind.PDF
        documentKind() == ImportFileKind.QIF -> StrategyKind.QIF
        documentKind() == ImportFileKind.OFX -> StrategyKind.OFX
        isXlsxStrategy() -> StrategyKind.XLSX
        else -> StrategyKind.CSV
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

/**
 * Unresolved references found while parsing an incoming artifact, to be resolved before applying, and the names of
 * the entities the artifact itself creates ([provides], such as the currency list's codes), which resolve those
 * references in the other artifacts installed with it.
 */
data class StrategyParseResult(
    val key: StrategyKey,
    val unresolvedReferences: List<CsvUnresolvedReference>,
    val provides: Map<CsvReferenceType, Set<String>> = emptyMap(),
)

/**
 * Maps library artifacts to/from their remote filenames. The filename suffix encodes the [StrategyKind]
 * so the destination is knowable from the name alone (e.g. `Wise.csv.json`, `Wise.api.json`).
 */
object StrategyFileNaming {
    const val GLOBAL_MAPPINGS_NAME: String = "global-account-mappings"

    /** The name of the single [StrategyKind.ASSETS] artifact. */
    const val ASSETS_NAME: String = "assets"

    /** The name of the single [StrategyKind.CURRENCIES] artifact. */
    const val CURRENCIES_NAME: String = "ISO 4217 currencies"

    /** The name of the single [StrategyKind.CRYPTO_ASSETS] artifact. */
    const val CRYPTO_ASSETS_NAME: String = "Crypto assets"
    private const val SUFFIX = ".json"
    private const val CSV_INFIX = ".csv"
    private const val QIF_INFIX = ".qif"
    private const val OFX_INFIX = ".ofx"
    private const val XLSX_INFIX = ".xlsx"
    private const val PDF_INFIX = ".pdf"
    private const val API_INFIX = ".api"
    private const val PASS_THROUGH_INFIX = ".passthrough"
    private const val CURRENCIES_INFIX = ".currencies"
    private const val CRYPTO_ASSETS_INFIX = ".crypto"
    private const val HOLIDAY_CALENDAR_INFIX = ".calendar"

    fun fileName(key: StrategyKey): String =
        when (key.kind) {
            StrategyKind.CSV -> "${key.name}$CSV_INFIX$SUFFIX"
            StrategyKind.QIF -> "${key.name}$QIF_INFIX$SUFFIX"
            StrategyKind.OFX -> "${key.name}$OFX_INFIX$SUFFIX"
            StrategyKind.XLSX -> "${key.name}$XLSX_INFIX$SUFFIX"
            StrategyKind.PDF -> "${key.name}$PDF_INFIX$SUFFIX"
            StrategyKind.API -> "${key.name}$API_INFIX$SUFFIX"
            StrategyKind.GLOBAL_MAPPINGS -> "$GLOBAL_MAPPINGS_NAME$SUFFIX"
            StrategyKind.PASS_THROUGH -> "${key.name}$PASS_THROUGH_INFIX$SUFFIX"
            StrategyKind.CURRENCIES -> "${key.name}$CURRENCIES_INFIX$SUFFIX"
            StrategyKind.HOLIDAY_CALENDAR -> "${key.name}$HOLIDAY_CALENDAR_INFIX$SUFFIX"
            StrategyKind.ASSETS -> "$ASSETS_NAME$SUFFIX"
            StrategyKind.CRYPTO_ASSETS -> "${key.name}$CRYPTO_ASSETS_INFIX$SUFFIX"
        }

    /** Parses a remote filename back into a [StrategyKey], or null if it isn't a library file. */
    fun parse(fileName: String): StrategyKey? {
        if (!fileName.endsWith(SUFFIX)) return null
        val stem = fileName.removeSuffix(SUFFIX)
        if (stem == GLOBAL_MAPPINGS_NAME) {
            return StrategyKey(StrategyKind.GLOBAL_MAPPINGS, GLOBAL_MAPPINGS_NAME)
        }
        if (stem == ASSETS_NAME) return StrategyKey(StrategyKind.ASSETS, ASSETS_NAME)
        return when {
            stem.endsWith(XLSX_INFIX) -> {
                StrategyKey(StrategyKind.XLSX, stem.removeSuffix(XLSX_INFIX))
            }

            stem.endsWith(PDF_INFIX) -> {
                StrategyKey(StrategyKind.PDF, stem.removeSuffix(PDF_INFIX))
            }

            stem.endsWith(CSV_INFIX) -> {
                StrategyKey(StrategyKind.CSV, stem.removeSuffix(CSV_INFIX))
            }

            stem.endsWith(QIF_INFIX) -> {
                StrategyKey(StrategyKind.QIF, stem.removeSuffix(QIF_INFIX))
            }

            stem.endsWith(OFX_INFIX) -> {
                StrategyKey(StrategyKind.OFX, stem.removeSuffix(OFX_INFIX))
            }

            stem.endsWith(API_INFIX) -> {
                StrategyKey(StrategyKind.API, stem.removeSuffix(API_INFIX))
            }

            stem.endsWith(PASS_THROUGH_INFIX) -> {
                StrategyKey(StrategyKind.PASS_THROUGH, stem.removeSuffix(PASS_THROUGH_INFIX))
            }

            stem.endsWith(CURRENCIES_INFIX) -> {
                StrategyKey(StrategyKind.CURRENCIES, stem.removeSuffix(CURRENCIES_INFIX))
            }

            stem.endsWith(CRYPTO_ASSETS_INFIX) -> {
                StrategyKey(StrategyKind.CRYPTO_ASSETS, stem.removeSuffix(CRYPTO_ASSETS_INFIX))
            }

            stem.endsWith(HOLIDAY_CALENDAR_INFIX) -> {
                StrategyKey(StrategyKind.HOLIDAY_CALENDAR, stem.removeSuffix(HOLIDAY_CALENDAR_INFIX))
            }

            else -> {
                null
            }
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
