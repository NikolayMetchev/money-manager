package com.moneymanager.csvimporter

import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.ColumnRecord
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ensures a crypto asset exists for every crypto ticker appearing in [strategy]'s currency-lookup
 * columns across [rows], creating any missing ones via the engine (upsert = idempotent), and returns
 * the full crypto-asset set to feed the mapper. Public so the single-file apply path (ApplyStrategyDialog)
 * can resolve + create crypto the same way the bulk/re-import paths do.
 *
 * **Any** value in a currency-lookup column that is not a known fiat currency is treated as a crypto
 * asset and auto-created, using the catalog's display name (or the ticker itself when unknown), so a
 * crypto ticker resolves instead of failing with "Currency not found". This is unconditional — it does
 * not depend on any per-strategy flag, so it holds even for strategies stored before crypto support.
 * Every asset is created at the fixed 18-decimal crypto scale, so any observed precision parses
 * exactly via `Money.fromDisplayValue` (which throws on excess precision). Returns empty when no
 * [cryptoRepository] is supplied.
 */
suspend fun ensureCryptoAssets(
    strategy: CsvImportStrategy,
    columns: List<CsvColumn>,
    fileName: String,
    rows: List<CsvRow>,
    currencies: List<Currency>,
    importEngine: ImportEngine,
    cryptoRepository: CryptoReadRepository?,
): List<CryptoAsset> =
    ensureScannedCryptoAssets(strategy, importEngine, cryptoRepository, currencies) {
        collectAssetCodes(strategy, columns, fileName) { rows }
    }

internal suspend fun ensureScannedCryptoAssets(
    strategy: CsvImportStrategy,
    importEngine: ImportEngine,
    cryptoRepository: CryptoReadRepository?,
    currencies: List<Currency>,
    readCodes: suspend () -> Set<String>,
): List<CryptoAsset> {
    if (cryptoRepository == null) return emptyList()
    val assets = cryptoRepository.getAllCryptoAssets().first()
    val known = KnownAssets(currencies, assets, cryptoRepository.assetAliasMap())
    val missing = known.missingCodes(strategy, readCodes())
    if (missing.isEmpty()) return assets
    createCryptoAssets(missing, importEngine)
    return cryptoRepository.getAllCryptoAssets().first()
}

internal data class PreparedAssetScan(
    val staged: CsvImport,
    val strategy: CsvImportStrategy,
    val codes: Set<String>,
)

internal fun CsvImport.hasSameRowsAs(other: CsvImport): Boolean =
    id == other.id &&
        tableName == other.tableName &&
        originalFileName == other.originalFileName &&
        fileChecksum == other.fileChecksum &&
        columns == other.columns &&
        rowCount == other.rowCount

/**
 * Prepares a whole batch of [imports] before any file is imported, the files in parallel: stages each file as the
 * strategy it matches reads it (a PDF's rows assembled by its line rules, an Excel file's worksheet), then
 * pre-creates every crypto asset the batch needs, which the per-file [ensureCryptoAssets] cannot do: a fiat file
 * processed before the crypto file that introduces a ticker would fail with "Currency not found". Creating
 * everything up front removes that ordering dependency. Restaging is written one file at a time, as the engine
 * doesn't serialize writes. [onPrepared] hears of each file, with its index in [imports], as it's done, in the
 * order they finish. Returns the scanned codes and their file and strategy so bulk apply can reuse the scan.
 */
@Suppress("LongParameterList")
internal suspend fun prepareImports(
    imports: List<CsvImport>,
    strategies: List<CsvImportStrategy>,
    currencies: List<Currency>,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
    cryptoRepository: CryptoReadRepository?,
    onPrepared: suspend (index: Int, file: CsvImport) -> Unit = { _, _ -> },
): Map<CsvImportId, PreparedAssetScan> {
    val known = cryptoRepository?.let { KnownAssets(currencies, it.getAllCryptoAssets().first(), it.assetAliasMap()) }
    val writes = Mutex()
    val reports = Mutex()
    val scans =
        coroutineScope {
            imports
                .mapIndexed { index, listed ->
                    async(Dispatchers.Default) {
                        val staged = stageForStrategy(listed, strategies, csvImportRepository, importEngine, writes)
                        val scan =
                            if (staged == null || known == null) {
                                null
                            } else {
                                PreparedAssetScan(
                                    staged.first,
                                    staged.second,
                                    assetCodesForImport(staged.first, staged.second, csvImportRepository),
                                )
                            }
                        reports.withLock { onPrepared(index, listed) }
                        scan
                    }
                }.awaitAll()
                .filterNotNull()
        }
    if (known != null) createCryptoAssets(scans.flatMapTo(mutableSetOf()) { known.missingCodes(it.strategy, it.codes) }, importEngine)
    return scans.associateBy { it.staged.id }
}

// [listed] staged as the strategy it matches reads it, with that strategy; null when none matches. [writes] keeps
// the restaging writes to one at a time.
private suspend fun stageForStrategy(
    listed: CsvImport,
    strategies: List<CsvImportStrategy>,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
    writes: Mutex,
): Pair<CsvImport, CsvImportStrategy>? {
    val full = csvImportRepository.getImport(listed.id).first() ?: return null
    val strategy =
        full.lastAppliedStrategyId?.let { id -> strategies.find { it.id == id } }
            ?: strategies.selectForImport(full, csvImportRepository)
            ?: return null
    // Assembling a PDF's rows is the slow part, so only its write waits for the others.
    val sheetStaged = writes.withLock { restageXlsxForStrategy(full, strategy, csvImportRepository, importEngine) }
    val staged =
        (planSqliteRestage(sheetStaged, strategy, csvImportRepository) ?: planPdfRestage(sheetStaged, strategy, csvImportRepository))
            ?.let { restage -> writes.withLock { restage.apply(importEngine, csvImportRepository) } }
            ?: sheetStaged
    return staged to strategy
}

private suspend fun assetCodesForImport(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
): Set<String> {
    // Scan ALL rows, not just importable ones: a re-import remaps already-IMPORTED rows too (value
    // updates, transfer→trade conversions), and their tickers must resolve at plan time. For a fresh
    // import every row is unimported, so this is the same set.
    return collectAssetCodes(strategy, csvImport.columns, csvImport.originalFileName) {
        csvImportRepository.getImportRows(csvImport.id, limit = csvImport.rowCount.coerceAtLeast(1), offset = 0)
    }
}

// The database's assets as [resolveAssetCode] reads them: codes, aliases and names (currencies first).
private class KnownAssets(
    currencies: List<Currency>,
    crypto: List<CryptoAsset>,
    private val aliases: Map<String, String>,
) {
    private val codes = (currencies.map { it.code } + crypto.map { it.code }).mapTo(mutableSetOf()) { it.uppercase() }
    private val codeByName = lazy { codesByName(currencies.map { it.code to it.name } + crypto.map { it.code to it.name }) }

    private fun isAsset(code: String): Boolean = code in codes

    fun missingCodes(
        strategy: CsvImportStrategy,
        rawCodes: Set<String>,
    ): Set<String> =
        rawCodes.mapNotNullTo(mutableSetOf()) { raw ->
            val code = resolveAssetCode(strategy.config.assetCodes.canonical(raw), aliases, codeByName, ::isAsset)
            code.takeIf { it.isNotEmpty() && !isAsset(it) }
        }
}

/**
 * Reads the distinct values the strategy uses as asset codes, after its record rewrites.
 * The values can be resolved again against a newer asset catalog without reopening the staged rows.
 */
internal suspend fun collectAssetCodes(
    strategy: CsvImportStrategy,
    columns: List<CsvColumn>,
    fileName: String,
    readRows: suspend () -> List<CsvRow>,
): Set<String> {
    val indexByName = columnIndexWithRuleColumns(columns, strategy.config)

    // Every value holding an asset code: the currency lookups plus a fee paid in its own asset.
    val lookups = listOf(TransferField.CURRENCY, TransferField.TO_CURRENCY).mapNotNull { strategy.config.fieldMappings[it] }
    val codeExprs =
        lookups.filterIsInstance<CurrencyLookupMapping>().map { it.value } +
            strategy.config.rules
                .flatMap { it.actions }
                .filterIsInstance<RuleAction.Fee>()
                .mapNotNull { it.fee.currency }
    val readableExprs = codeExprs.filter { expr -> expr.paths.any { it in indexByName } }
    if (readableExprs.isEmpty()) return emptySet()

    val rules = RuleEvaluator()
    val engine = RuleEngine(rules)
    // Only rewrites and actions that control evaluation affect the record left for the currency expressions.
    val recordRules =
        strategy.config.rules.mapNotNull { rule ->
            val actions =
                rule.actions.filter { action ->
                    when (action) {
                        is RuleAction.SwapValues, is RuleAction.SetValue, is RuleAction.Capture, RuleAction.Skip -> true
                        else -> false
                    }
                }
            rule.copy(actions = actions).takeIf { actions.isNotEmpty() }
        }
    val codes = mutableSetOf<String>()
    for (row in readRows()) {
        // The code is read as the strategy's rules leave the row, as the mapper reads it.
        val base = ColumnRecord(row.values.withFileName(indexByName, fileName), indexByName)
        val record = if (recordRules.isEmpty()) base else engine.evaluate(recordRules, base).record
        for (expr in readableExprs) {
            val raw = rules.resolve(expr) { record.text(it)?.trim() }
            if (raw.isNotBlank()) codes += raw
        }
    }
    return codes
}

/** Creates a crypto asset for each of [codes] (fixed 18-decimal scale; display name from the catalog). */
internal suspend fun createCryptoAssets(
    codes: Set<String>,
    importEngine: ImportEngine,
) {
    for (code in codes) {
        importEngine.createCrypto(code, name = CryptoRegistry.nameFor(code))
    }
}
