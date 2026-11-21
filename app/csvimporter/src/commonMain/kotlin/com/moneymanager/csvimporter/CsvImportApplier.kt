package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.ownedAttributeValues
import com.moneymanager.domain.model.csvstrategy.uniqueIdentifierAttributeTypes
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.ColumnRecord
import com.moneymanager.domain.model.rules.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.BatchRelationship
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.createAccountMappings
import com.moneymanager.importengineapi.createAccounts
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.deleteEmptyImportCreatedAccounts
import com.moneymanager.importengineapi.feeAccounts
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.tradeDedupePolicy
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.WeightedProgress
import com.moneymanager.xlsx.createXlsxParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("CsvImportApplier")

/**
 * Collapses consecutive duplicate accounts out of a pass-through spend-leg chain ([nodes] = the conduit
 * account ids followed by the merchant account id), keeping [spendDescriptions] aligned 1:1 with the
 * surviving conduit legs by dropping the description of each removed leg.
 *
 * The engine builds one spend leg per adjacent node pair (`nodes[i] -> nodes[i+1]`). A node that equals
 * its predecessor — e.g. a merchant that resolved to its feeding conduit, or a repeated conduit — would
 * make that leg a zero-movement `source == target` transfer, which the `transfer` CHECK constraint
 * rejects (aborting the whole file). Returns the collapsed (nodes, descriptions), or null when fewer
 * than two distinct nodes remain (no real pass-through — the caller imports the plain funding transfer).
 */
internal fun collapsePassThroughChain(
    nodes: List<AccountId>,
    spendDescriptions: List<String>,
): Pair<List<AccountId>, List<String>>? {
    if (nodes.isEmpty()) return null
    val keptNodes = mutableListOf(nodes.first())
    val keptDescriptions = mutableListOf<String>()
    for (legIndex in 0 until nodes.size - 1) {
        if (nodes[legIndex + 1] != keptNodes.last()) {
            keptNodes += nodes[legIndex + 1]
            keptDescriptions += spendDescriptions.getOrElse(legIndex) { "" }
        }
    }
    return if (keptNodes.size < 2) null else keptNodes to keptDescriptions
}

/**
 * The row's own account: a fee comes out of it, so a fee on money coming in isn't charged to whoever sent it, and a
 * balance the row reports is that account's.
 */
private fun CsvTransferWithAttributes.feePayerId(): AccountId =
    statementAccountId?.takeIf { it != UNRESOLVED_ACCOUNT_ID } ?: transfer.sourceAccountId

/** The account whose fee account the row names on its other side ([OWN_FEE_ACCOUNT_ID]), or null. */
private fun CsvTransferWithAttributes.ownerOfFeeAccountSide(): AccountId? =
    when (OWN_FEE_ACCOUNT_ID) {
        transfer.targetAccountId -> transfer.sourceAccountId
        transfer.sourceAccountId -> transfer.targetAccountId
        else -> null
    }

/** The row with [OWN_FEE_ACCOUNT_ID] replaced by its owner's fee account from [feeAccountOf]. */
private fun CsvTransferWithAttributes.withOwnFeeAccount(feeAccountOf: Map<AccountId, AccountId>): CsvTransferWithAttributes {
    val owner = ownerOfFeeAccountSide() ?: return this
    val feeAccount = checkNotNull(feeAccountOf[owner]) { "No fee account for $owner" }

    fun AccountId.resolved() = if (this == OWN_FEE_ACCOUNT_ID) feeAccount else this
    return copy(
        transfer =
            transfer.copy(
                sourceAccountId = transfer.sourceAccountId.resolved(),
                targetAccountId = transfer.targetAccountId.resolved(),
            ),
    )
}

/** Links each debit leg to the nearest credit leg of the same key within [window] (credits may be shared). */
private fun pairDebitsToCredits(
    legs: List<CsvTransferWithAttributes>,
    window: Duration,
    relationshipTypeName: String,
): List<Pair<Long, BatchRelationship>> {
    val credits = legs.filter { it.groupLeg!!.side == GroupLegSide.CREDIT }
    return legs
        .filter { it.groupLeg!!.side == GroupLegSide.DEBIT }
        .mapNotNull { debit ->
            val match =
                credits
                    .filter { it.groupLeg!!.key == debit.groupLeg!!.key }
                    .minByOrNull { (it.transfer.timestamp - debit.transfer.timestamp).absoluteValue }
                    ?.takeIf { (it.transfer.timestamp - debit.transfer.timestamp).absoluteValue <= window }
                    ?: return@mapNotNull null
            debit.rowIndex to BatchRelationship(ImportRowKey.CsvRow(match.rowIndex), relationshipTypeName)
        }
}

/**
 * Links every member leg to the anchor leg that shares its key, as the relationship's primary member. A leg with a blank
 * key (the row names no group) links to nothing, and an anchor never links to itself.
 */
private fun linkMembersToAnchors(
    legs: List<CsvTransferWithAttributes>,
    relationshipTypeName: String,
): List<Pair<Long, BatchRelationship>> {
    val (anchors, members) = legs.filter { it.groupLeg!!.key.isNotBlank() }.partition { it.groupLeg!!.funding }
    val anchorByKey = anchors.sortedBy { it.rowIndex }.groupBy { it.groupLeg!!.key }.mapValues { (_, group) -> group.first() }
    return members.mapNotNull { member ->
        val anchor = anchorByKey[member.groupLeg!!.key] ?: return@mapNotNull null
        member.rowIndex to BatchRelationship(ImportRowKey.CsvRow(anchor.rowIndex), relationshipTypeName)
    }
}

/**
 * Links each funding leg (from the funding leg) to the nearest unclaimed non-funding leg on the other side
 * with the same key within [window], one to one. Legs the source excluded (a refused deposit) record no
 * movement, so they pair with nothing.
 */
private fun pairFundingLegs(
    legs: List<CsvTransferWithAttributes>,
    window: Duration,
    relationshipTypeName: String,
): List<Pair<Long, BatchRelationship>> {
    val counted = legs.filter { leg -> leg.attributes.none { (name, _) -> name == EXCLUDED_ATTRIBUTE_TYPE_NAME } }
    val (funding, others) = counted.partition { it.groupLeg!!.funding }
    val claimed = mutableSetOf<Long>()
    return funding.sortedBy { it.transfer.timestamp }.mapNotNull { leg ->
        val match =
            others
                .filter {
                    it.rowIndex !in claimed &&
                        it.groupLeg!!.key == leg.groupLeg!!.key &&
                        it.groupLeg.side != leg.groupLeg.side &&
                        (it.transfer.timestamp - leg.transfer.timestamp).absoluteValue <= window
                }.minByOrNull { (it.transfer.timestamp - leg.transfer.timestamp).absoluteValue }
                ?: return@mapNotNull null
        claimed += match.rowIndex
        leg.rowIndex to BatchRelationship(ImportRowKey.CsvRow(match.rowIndex), relationshipTypeName)
    }
}

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

private suspend fun ensureScannedCryptoAssets(
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

private fun CsvImport.hasSameRowsAs(other: CsvImport): Boolean =
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
        planPdfRestage(sheetStaged, strategy, csvImportRepository)
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
private suspend fun collectAssetCodes(
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
private suspend fun createCryptoAssets(
    codes: Set<String>,
    importEngine: ImportEngine,
) {
    for (code in codes) {
        importEngine.createCrypto(code, name = CryptoRegistry.nameFor(code))
    }
}

/**
 * Applies the matching strategy to every [imports] file. Each file's strategy is auto-matched from its
 * column headers, so files from different banks each get the right strategy; files with no match are
 * skipped and counted. Payee/counterparty accounts auto-create with their detected names (no per-file
 * confirmation). For files whose strategy needs a user-chosen source (no SOURCE_ACCOUNT mapping), the
 * source account is [directoryAccounts]'s entry for that file if present (the import directory it was
 * scanned from, or that directory's parent), else the shared [sourceAccountOverride]; hard-coded and
 * per-row strategies resolve their own regardless. Refreshes materialized views once at the end unless
 * [refreshViews] is false (a caller batching several runs refreshes once itself). Reports row-weighted
 * run-wide progress via [onProgress], naming each file by [fileLabel]. [stopRequested] is asked before each file:
 * once it says yes, no more files start (the one under way finishes) and the rest are counted in
 * [BulkApplyResult.filesNotStarted].
 */
@Suppress("LongParameterList")
suspend fun bulkApplyCsv(
    imports: List<CsvImport>,
    sourceAccountOverride: AccountId?,
    strategies: List<CsvImportStrategy>,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: suspend (Progress) -> Unit,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    cryptoRepository: CryptoReadRepository? = null,
    attributeAccountMatchers: Map<String, AttributeAccountMatcher> = emptyMap(),
    directoryAccounts: Map<CsvImportId, AccountId> = emptyMap(),
    tradeRepository: TradeReadRepository? = null,
    refreshViews: Boolean = true,
    fileLabel: (CsvImport) -> String = { it.originalFileName },
    stopRequested: () -> Boolean = { false },
): BulkApplyResult {
    val outcomes = mutableListOf<BulkFileOutcome>()
    // Each imported file's result by its outcome's index, to fold once the match sync has run.
    val results = mutableMapOf<Int, CsvImportResult>()
    var filesNotStarted = 0

    // Preparing a file reads every one of its rows (a PDF's lines, until they're assembled), so they weigh it.
    val tracker =
        WeightedProgress(
            weights = imports.map { it.rowCount },
            preparation = imports.map { it.rowCount },
            preparationNoun = "files",
            onProgress = onProgress,
        )
    tracker.started(detail = "Preparing")

    // Stage every file and create every crypto asset the batch needs up front, sized to the batch-wide max precision
    // per ticker, so cross-file ordering never leaves a ticker missing or a scale factor too small.
    val scans =
        prepareImports(imports, strategies, currencies, csvImportRepository, importEngine, cryptoRepository) { index, file ->
            tracker.prepared(index, "Prepared", fileLabel(file))
        }

    imports.forEachIndexed { index, listedImport ->
        // Once stopped, every remaining file is counted rather than started.
        if (filesNotStarted > 0 || stopRequested()) {
            filesNotStarted++
            return@forEachIndexed
        }
        val label = fileLabel(listedImport)
        tracker.itemStarted(index, label)
        // getAllImports() doesn't populate columns, so re-fetch the full import (which loads them)
        // for the strategy match below.
        val csvImport = csvImportRepository.getImport(listedImport.id).first() ?: listedImport
        val matched = strategies.selectForImport(csvImport, csvImportRepository)

        fun outcome(
            status: BulkFileStatus,
            result: CsvImportResult? = null,
            error: String? = null,
        ) = outcomes.add(
            BulkFileOutcome(
                fileId = csvImport.id.toString(),
                fileName = csvImport.originalFileName,
                status = status,
                strategyName = matched?.name,
                transfersCreated = result?.successCount ?: 0,
                duplicatesSkipped = result?.duplicateCount ?: 0,
                error = error,
            ),
        )
        if (matched == null) {
            outcome(BulkFileStatus.SKIPPED_NO_STRATEGY)
            return@forEachIndexed
        }
        try {
            val result =
                applyStagedCsv(
                    csvImport = csvImport,
                    strategy = matched,
                    sourceAccountOverride = directoryAccounts[csvImport.id] ?: sourceAccountOverride,
                    currencies = currencies,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    csvImportRepository = csvImportRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    refreshViews = false,
                    passThroughAccounts = passThroughAccounts,
                    onProgress = { tracker.item(index, label, it) },
                    engineBatchSize = BULK_ENGINE_BATCH_SIZE,
                    cryptoRepository = cryptoRepository,
                    attributeAccountMatchers = attributeAccountMatchers,
                    tradeRepository = tradeRepository,
                    syncMatches = false,
                    preparedAssetCodes =
                        scans[csvImport.id]
                            ?.takeIf { it.strategy == matched && it.staged.hasSameRowsAs(csvImport) }
                            ?.codes,
                )
            if (result == null) {
                outcome(BulkFileStatus.NOTHING_TO_IMPORT)
            } else {
                results[outcomes.size] = result
                outcome(BulkFileStatus.IMPORTED, result)
            }
        } catch (expected: Exception) {
            logger.e(expected) { "Bulk CSV import failed for ${csvImport.originalFileName}: ${expected.message}" }
            outcome(BulkFileStatus.FAILED, error = expected.message ?: expected::class.simpleName)
        }
    }

    if (results.isNotEmpty()) {
        val gone = importEngine.import(ImportBatch(syncMatches = true)).matchSync.deleted
        results.forEach { (index, result) ->
            val folded = result.foldedInto(gone)
            outcomes[index] = outcomes[index].copy(transfersCreated = folded.successCount, duplicatesSkipped = folded.duplicateCount)
        }
    }
    tracker.done(imports.size - filesNotStarted)
    if (refreshViews) maintenance.refreshMaterializedViews()

    return BulkApplyResult(outcomes, filesNotStarted)
}

/**
 * Applies a fixed [strategy] to all importable rows of an already-staged [csvImport] and returns the
 * run result (null when there are no rows left to import). Unlike [bulkApplyCsv] the strategy is given,
 * not auto-matched — used by re-import and by [bulkApplyCsv] itself after it selects one.
 * [refreshViews] = false lets a caller batch many files and refresh once at the end.
 * [onProgress]/[engineBatchSize] are forwarded to the engine so a caller can show per-chunk progress.
 * [syncMatches] = false leaves re-matching the stored observations ([ImportBatch.syncMatches]) to the caller, which
 * runs it once after many files.
 */
@Suppress("LongParameterList")
suspend fun applyStagedCsv(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    refreshViews: Boolean,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (Progress) -> Unit)? = null,
    engineBatchSize: Int = Int.MAX_VALUE,
    cryptoRepository: CryptoReadRepository? = null,
    attributeAccountMatchers: Map<String, AttributeAccountMatcher> = emptyMap(),
    tradeRepository: TradeReadRepository? = null,
    syncMatches: Boolean = true,
    // Raw codes from the batch preflight for this file and strategy.
    preparedAssetCodes: Set<String>? = null,
): CsvImportResult? {
    // The staged rows must be the ones the strategy reads: an Excel import is initially staged from the workbook's
    // FIRST worksheet, but the strategy may target a different sheet, and a PDF import's rows are whatever its
    // strategy's line rules assemble. Re-stage before reading rows so everything downstream sees those.
    val stagedImport = restageForStrategy(csvImport, strategy, csvImportRepository, importEngine)

    onProgress?.invoke(Progress("Loading rows"))
    val allRows = csvImportRepository.getImportRows(stagedImport.id, limit = stagedImport.rowCount.coerceAtLeast(1), offset = 0)
    val unprocessedRows = allRows.filter { it.importStatus == null || it.importStatus == ImportStatus.ERROR }
    // A strategy that assembles a trade from a whole row group must see the group whole: mapping only
    // the unprocessed rows would turn a group with one errored leg into a one-legged group and assemble
    // it wrongly (or not at all). Re-mapping the settled legs costs a pass and changes nothing — the
    // trade's exact-tuple idempotency makes re-emitting the group a no-op that resolves to DUPLICATE.
    // Transfers are still emitted only for the unprocessed rows (see [unprocessedRowIndexes] below), so
    // widening the mapped set changes what groups see and nothing else.
    val rows = if (strategy.config.legGroups.any { it.assembly is LegAssembly.Trade }) allRows else unprocessedRows
    val unprocessedRowIndexes: Set<Long> = unprocessedRows.mapTo(mutableSetOf()) { it.rowIndex }
    if (unprocessedRows.isEmpty()) {
        // A genuinely empty file (a header-only export with no data rows) has nothing to import, but
        // must still be marked applied or it reappears in the Unimported tab on every "Import all".
        // Record the strategy application once (the lastAppliedAt guard keeps repeated runs a no-op).
        // Scoped to allRows.isEmpty() so it never fires on re-import, which always has rows and whose
        // "nothing new to import" outcome must stay a null result.
        if (allRows.isEmpty() && stagedImport.lastAppliedAt == null) {
            recordCsvApplication(importEngine, stagedImport.id, strategy, sourceAccountOverride)
            return CsvImportResult(successCount = 0, failedRows = emptyList())
        }
        return null
    }

    // The shared override only applies to strategies that need a user-chosen source. A hard-coded
    // mapping resolves its own account; a per-row mapping decides per row.
    val effectiveSource = effectiveSourceFor(strategy, sourceAccountOverride)

    // On-demand: create a crypto asset for every non-fiat ticker appearing in the strategy's currency
    // column, so rows denominated in crypto resolve to a real crypto asset (upsert = idempotent on
    // re-import). No-op unless a CryptoReadRepository is supplied and the currency column carries crypto.
    onProgress?.invoke(Progress("Mapping rows"))
    val cryptoAssets =
        ensureScannedCryptoAssets(strategy, importEngine, cryptoRepository, currencies) {
            preparedAssetCodes?.takeIf { stagedImport === csvImport && rows.size == allRows.size }
                ?: collectAssetCodes(strategy, stagedImport.columns, stagedImport.originalFileName) { rows }
        }
    val assetAliases = cryptoRepository?.assetAliasMap().orEmpty()

    // Re-fetch accounts so payee accounts created by earlier files in the same scan are seen.
    val accounts = accountRepository.accountsVisibleTo(strategy)
    val mappings = accountMappingRepository.getAllMappings().first()
    val historicalAccountNames = accountRepository.getPreviousAccountNames()
    val basePrep =
        buildCsvMapper(
            strategy,
            stagedImport.columns,
            accounts,
            currencies,
            mappings,
            effectiveSource,
            passThroughAccounts,
            historicalAccountNames,
            cryptoAssets,
            attributeAccountMatchers,
            assetAliases,
            stagedImport.originalFileName,
            accountRepository.getAccountOwnerNames(),
        ).prepareImport(rows)

    val result =
        runCsvImport(
            cryptoAssets = cryptoAssets,
            assetAliases = assetAliases,
            csvImport = stagedImport,
            rows = rows,
            columns = stagedImport.columns,
            strategy = strategy,
            basePrep = basePrep,
            selectedExistingAccounts = emptyMap(),
            selectedNewAccountNames = emptyMap(),
            selectedSourceAccountId = effectiveSource,
            currencies = currencies,
            accountMappingRepository = accountMappingRepository,
            accountRepository = accountRepository,
            maintenance = maintenance,
            importEngine = importEngine,
            refreshViews = refreshViews,
            passThroughAccounts = passThroughAccounts,
            onProgress = onProgress,
            engineBatchSize = engineBatchSize,
            attributeAccountMatchers = attributeAccountMatchers,
            unprocessedRowIndexes = unprocessedRowIndexes,
            tradeRepository = tradeRepository,
            visibleAccounts = accounts,
            foreignFiles = crossFileLegRows(stagedImport, strategy, csvImportRepository),
        )
    // Read after the run, which may have saved mappings: a re-import compares against what this run leaves.
    val inputsHash =
        stagedInputsHash(
            strategy = strategy,
            sourceAccount = effectiveSource,
            mappings = accountMappingRepository.getAllMappings().first(),
            assetAliases = cryptoRepository?.assetAliasMap().orEmpty(),
            attributeAccountMatchers = attributeAccountMatchers,
            passThroughAccounts = passThroughAccounts,
        )
    importEngine.applyCsvImportMutations(listOf(recordRowsMutation(stagedImport, allRows, inputsHash)))
    // An account the file's rules named that nothing ended up on (a conversion counterparty whose legs paired into a
    // trade) goes, as a re-run of the file removes it: a fresh import and a re-import then hold the same accounts.
    importEngine.deleteEmptyImportCreatedAccounts(
        createdAccountIds = csvImportRepository.getAccountsCreatedByImport(stagedImport.id),
        source = Source.Csv(stagedImport.id),
        accountRepository = accountRepository,
        tradeRepository = tradeRepository,
    )
    val created =
        csvImportRepository
            .getImportRows(stagedImport.id, limit = stagedImport.rowCount.coerceAtLeast(1), offset = 0)
            .filter { it.rowIndex in unprocessedRowIndexes && it.importStatus == ImportStatus.IMPORTED }
            .mapNotNullTo(mutableSetOf()) { it.transferId }
    val booked = result.copy(createdTransferIds = created)
    if (!syncMatches) return booked
    onProgress?.invoke(Progress("Matching observations"))
    return booked.foldedInto(importEngine.import(ImportBatch(syncMatches = true)).matchSync.deleted)
}

/**
 * [csvImport] staged as [strategy] reads it (see [restageXlsxForStrategy] and [restagePdfForStrategy]): reloaded
 * when its rows had to be staged again, else as it was.
 */
suspend fun restageForStrategy(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
): CsvImport =
    restagePdfForStrategy(
        restageXlsxForStrategy(csvImport, strategy, csvImportRepository, importEngine),
        strategy,
        csvImportRepository,
        importEngine,
    )

private suspend fun restagePdfForStrategy(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
): CsvImport = planPdfRestage(csvImport, strategy, csvImportRepository)?.apply(importEngine, csvImportRepository) ?: csvImport

/** The rows the strategy assembles from a PDF's staged text, or null when the staging can be used as it is. */
suspend fun planPdfRestage(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
): CsvImportMutation.Restage? {
    val pdf = csvImportRepository.getPdfSource(csvImport.id) ?: return null
    val lineConfig = strategy.config.pdfLines
    val rulesHash = lineConfig?.let { PdfRowAssembler.rulesHash(it) }
    if (pdf.stagedRulesHash == rulesHash) return null
    if (csvImport.lastAppliedAt != null) {
        // Its transactions point at the staged rows by index; a re-import reserves them before replacing the rows.
        logger.w { "PDF import ${csvImport.id.id} was imported with other line rules; keeping its rows until it's re-imported" }
        return null
    }
    val staged = lineConfig?.let { PdfRowAssembler(it).assemble(pdf.lines) } ?: PdfRowAssembler.rawLines(pdf.lines)
    return CsvImportMutation.Restage(csvImport.id, staged.headers, staged.rows, pdfRulesHash = rulesHash)
}

private suspend fun restageXlsxForStrategy(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
): CsvImport = planXlsxRestage(csvImport, strategy, csvImportRepository)?.apply(importEngine, csvImportRepository) ?: csvImport

/** The rows of the strategy's worksheet when it differs from the staged sheet. */
suspend fun planXlsxRestage(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
): CsvImportMutation.Restage? {
    val targetSheet = strategy.worksheetName ?: return null
    val blob = csvImportRepository.getXlsxBlob(csvImport.id) ?: return null
    if (blob.worksheetName == targetSheet) return null
    val parsed = createXlsxParser().parse(blob.fileBytes, targetSheet)
    return CsvImportMutation.Restage(csvImport.id, parsed.headers, parsed.rows, worksheetName = targetSheet)
}

private suspend fun CsvImportMutation.Restage.apply(
    importEngine: ImportEngine,
    reads: CsvImportReadRepository,
): CsvImport? {
    importEngine.applyCsvImportMutations(listOf(this))
    logger.i { "Re-staged import $id with ${rows.size} rows" }
    return reads.getImport(id).first()
}

/**
 * Records that [strategy] was applied to [csvImportId], moving the file into the "Imported" tab
 * (`last_applied_at` becomes non-null), with the source account and currency the run used, so a
 * re-import runs with them again rather than asking. Failures are logged, not thrown — the transfers
 * are already committed, so a missing history row must not fail the import.
 */
private suspend fun recordCsvApplication(
    importEngine: ImportEngine,
    csvImportId: CsvImportId,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
) {
    runCatching {
        importEngine.applyCsvImportMutations(
            listOf(
                CsvImportMutation.RecordApplication(
                    id = csvImportId,
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    appliedAt = Clock.System.now(),
                    accountId = effectiveSourceFor(strategy, sourceAccountOverride),
                    currencyId = (strategy.config.fieldMappings[TransferField.CURRENCY] as? HardCodedCurrencyMapping)?.currencyId,
                ),
            ),
        )
    }.onFailure { error ->
        logger.w { "Import application history could not be recorded for import $csvImportId: ${error.message}" }
    }
}

/**
 * Resolves the source account to use for a file under [strategy]:
 * - a [HardCodedAccountMapping] resolves its own account (the override is ignored),
 * - a per-row SOURCE_ACCOUNT mapping decides per row (null override),
 * - no SOURCE_ACCOUNT mapping falls back to the shared [override].
 */
fun effectiveSourceFor(
    strategy: CsvImportStrategy,
    override: AccountId?,
): AccountId? =
    when (val mapping = strategy.config.fieldMappings[TransferField.SOURCE_ACCOUNT]) {
        is HardCodedAccountMapping -> mapping.accountId
        null -> override
        else -> null
    }

/** True when `strategy` needs a user-chosen source account (no SOURCE_ACCOUNT mapping of its own). */
fun CsvImportStrategy.needsSourceAccountOverride(): Boolean = config.fieldMappings[TransferField.SOURCE_ACCOUNT] == null

fun buildCsvMapper(
    strategy: CsvImportStrategy,
    columns: List<CsvColumn>,
    accounts: List<Account>,
    currencies: List<Currency>,
    accountMappings: List<AccountMapping>,
    sourceAccountOverride: AccountId?,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    historicalAccountNames: Map<String, AccountId> = emptyMap(),
    cryptoAssets: List<CryptoAsset> = emptyList(),
    attributeAccountMatchers: Map<String, AttributeAccountMatcher> = emptyMap(),
    assetAliases: Map<String, String> = emptyMap(),
    fileName: String? = null,
    accountOwnerNames: Map<AccountId, List<String>> = emptyMap(),
): CsvTransferMapper =
    CsvTransferMapper(
        strategy = strategy,
        columns = columns,
        existingAccounts = accounts.associateBy { it.name },
        existingCurrencies = currencies.associateBy { it.id },
        existingCurrenciesByCode = currencies.associateBy { it.code.uppercase() },
        existingCryptoByCode = cryptoAssets.associateBy { it.code.uppercase() },
        assetAliases = assetAliases,
        accountMappings = accountMappings,
        historicalAccountNames = historicalAccountNames,
        sourceAccountOverride = sourceAccountOverride,
        passThroughDetector = passThroughAccounts.takeIf { it.isNotEmpty() }?.let { PassThroughDetector(it) },
        attributeAccountMatchers = attributeAccountMatchers,
        fileName = fileName,
        accountOwnerNames = accountOwnerNames,
    )

/**
 * Saves [mappings] in a single batch, falling back to per-mapping saves if the atomic batch fails so
 * one bad mapping doesn't block the rest.
 */
private suspend fun persistMappingsWithFallback(
    importEngine: ImportEngine,
    mappings: List<AccountMapping>,
) {
    if (mappings.isEmpty()) return
    try {
        importEngine.createAccountMappings(mappings)
        logger.i { "Saved ${mappings.size} account mappings" }
    } catch (expected: Exception) {
        // Batch is atomic, nothing was committed — retry per mapping so one bad mapping doesn't block the rest
        logger.w(expected) { "Bulk mapping save failed, falling back to per-mapping save" }
        for (mapping in mappings) {
            try {
                importEngine.createAccountMapping(
                    valuePattern = mapping.valuePattern,
                    accountId = mapping.accountId,
                    strategyId = mapping.strategyId,
                )
            } catch (expectedMappingError: Exception) {
                // Don't log the pattern itself — it's derived from bank-file payee/account values (PII).
                logger.w(expectedMappingError) { "Failed to save account mapping for account id ${mapping.accountId.id}" }
            }
        }
    }
}

/**
 * Creates the [accountsToCreate] (bulk, with per-account fallback) and returns the names actually
 * created. Failures are skipped — transfers referencing a missing account fail later.
 */
private suspend fun createNewAccounts(
    importEngine: ImportEngine,
    accountsToCreate: List<NewAccount>,
    csvImportId: CsvImportId,
    firstRowByAccountName: Map<String, Long>,
    shadowSource: String?,
): Set<String> {
    if (accountsToCreate.isEmpty()) return emptySet()
    val createdAccountNames = mutableSetOf<String>()
    val newAccounts =
        accountsToCreate.map { newAccount ->
            Account(
                id = AccountId(0),
                name = newAccount.name,
                openingDate = Clock.System.now(),
                categoryId = newAccount.categoryId,
            )
        }
    // CSV provenance pointing each account at the first row that referenced it (its creation row);
    // row-less when unknown.
    val sourceFor: (Account) -> Source = { account ->
        Source.Csv(csvImportId, firstRowByAccountName[account.name])
    }
    try {
        // Bulk-create all accounts in a single engine batch
        importEngine.createAccounts(newAccounts, shadowSource = shadowSource, sourceFor = sourceFor)
        newAccounts.forEach { account -> createdAccountNames.add(account.name) }
        logger.i { "Created ${accountsToCreate.size} new accounts" }
    } catch (expected: Exception) {
        // Bulk create failed — fall back to per-account creation to skip only failing accounts
        logger.w(expected) { "Bulk account creation failed, falling back to per-account creation" }
        for (account in newAccounts) {
            try {
                importEngine.createAccount(account, sourceFor(account), shadowSource)
                createdAccountNames.add(account.name)
                logger.i { "Created new account: ${account.name}" }
            } catch (expectedAccountError: Exception) {
                logger.w(expectedAccountError) { "Skipping account '${account.name}': ${expectedAccountError.message}" }
            }
        }
    }
    return createdAccountNames
}

/**
 * Maps each new account's final name to the index of the earliest row/record that referenced it (its
 * creation row), so account provenance can point at the relevant row. Keyed by the final (possibly
 * user-renamed) account name to match the accounts actually created. Shared with the QIF importer,
 * which reuses this CSV engine (QIF record indexes ARE these row indexes).
 */
fun buildFirstRowByAccountName(
    preparation: ImportPreparation,
    newAccountNames: Map<String, String>,
): Map<String, Long> {
    val firstRow = mutableMapOf<String, Long>()

    fun record(
        name: String,
        rowIndex: Long,
    ) {
        val finalName = name.trim()
        if (finalName.isNotBlank() && finalName !in firstRow) firstRow[finalName] = rowIndex
    }
    preparation.validTransfers
        .sortedBy { it.rowIndex }
        .forEach { transfer ->
            transfer.discoveredMappings.forEach { mapping ->
                record(newAccountNames[mapping.targetAccountName] ?: mapping.targetAccountName, transfer.rowIndex)
            }
            // Pass-through (conduit) rows create the conduit + merchant accounts outside the discovered-
            // mapping path, so record their originating row too — otherwise their provenance has no row
            // and the audit "Row" link points at the non-existent row 0.
            transfer.passThrough?.let { pt ->
                (pt.conduitNames + pt.merchantName).forEach { record(it, transfer.rowIndex) }
            }
        }
    return firstRow
}

/**
 * Turns any row still carrying [UNRESOLVED_ACCOUNT_ID] after the post-creation re-map into an error
 * row. By then every account a row needs exists, so a surviving placeholder means the row's account
 * could not be resolved; importing it would violate the transfer's account foreign key and abort the
 * whole file, losing the rows that are fine.
 */
private fun ImportPreparation.withUnresolvedAccountsAsErrors(): ImportPreparation {
    val (unresolved, resolved) =
        validTransfers.partition {
            it.transfer.sourceAccountId == UNRESOLVED_ACCOUNT_ID || it.transfer.targetAccountId == UNRESOLVED_ACCOUNT_ID
        }
    if (unresolved.isEmpty()) return this
    logger.w { "Skipping ${unresolved.size} row(s) whose account could not be resolved after account creation" }
    return copy(
        validTransfers = resolved,
        errorRows =
            errorRows +
                unresolved.map {
                    MappingResult.Error(it.rowIndex, "Account could not be resolved; the row references an account that does not exist")
                },
    )
}

private fun ImportPreparation.skippedRowMutations(id: CsvImportId): List<CsvImportMutation> =
    if (skippedRowIndexes.isEmpty()) {
        emptyList()
    } else {
        listOf(
            CsvImportMutation.UpdateRowStatuses(id, ImportStatus.SKIPPED.name, skippedRowIndexes),
            CsvImportMutation.ClearErrors(id, skippedRowIndexes),
        )
    }

/**
 * Applies [strategy] to [rows] of a single [csvImport] and writes back per-row statuses. Creates new
 * accounts the user accepted, persists/auto-captures account mappings, runs the central import engine,
 * and records the strategy application. Shared by the single-file dialog and the bulk path; the bulk
 * path passes [refreshViews] = false and refreshes once at the end.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
suspend fun runCsvImport(
    csvImport: CsvImport,
    rows: List<CsvRow>,
    columns: List<CsvColumn>,
    strategy: CsvImportStrategy,
    basePrep: ImportPreparation,
    selectedExistingAccounts: Map<String, AccountId>,
    selectedNewAccountNames: Map<String, String>,
    selectedSourceAccountId: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    refreshViews: Boolean = true,
    cryptoAssets: List<CryptoAsset> = emptyList(),
    assetAliases: Map<String, String> = emptyMap(),
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (Progress) -> Unit)? = null,
    engineBatchSize: Int = Int.MAX_VALUE,
    attributeAccountMatchers: Map<String, AttributeAccountMatcher> = emptyMap(),
    // Rows still awaiting import. Equals every row index in [rows] except when the strategy assembles
    // trades from row groups, where [rows] is widened to the whole file so a group is never seen with
    // some of its legs missing; transfers are then still emitted only for the rows named here.
    unprocessedRowIndexes: Set<Long> = rows.mapTo(mutableSetOf()) { it.rowIndex },
    tradeRepository: TradeReadRepository? = null,
    foreignFiles: List<ForeignFile> = emptyList(),
    // Supplies the account snapshot used by a freshly computed basePrep for these exact rows and settings.
    visibleAccounts: List<Account>? = null,
): CsvImportResult {
    logger.i { "Starting CSV import with ${basePrep.validTransfers.size} valid transfers" }

    // A reconciliation source may only be pointed at its own shadow accounts; drop any "map to existing
    // account" choice that names a real one (the row then creates its shadow account as usual).
    val knownAccounts = visibleAccounts ?: accountRepository.accountsVisibleTo(strategy)
    val visibleAccountsById = knownAccounts.associateBy { it.id }
    val existingSelections = selectedExistingAccounts.filterValues { it in visibleAccountsById }
    val accountsToCreate =
        buildAccountsToCreate(
            preparation = basePrep,
            existingAccountSelections = existingSelections,
            newAccountNames = selectedNewAccountNames,
        )
    val selectedMappingsToPersist =
        buildPendingAccountMappings(
            preparation = basePrep,
            accountSelections = existingSelections,
            accountsById = visibleAccountsById,
        )
    persistMappingsWithFallback(importEngine, selectedMappingsToPersist)

    // Create new accounts first (skip failures - transfers using them will fail later).
    // Record CSV provenance pointing each account at the first row that referenced it.
    val firstRowByAccountName = buildFirstRowByAccountName(basePrep, selectedNewAccountNames)
    createNewAccounts(
        importEngine = importEngine,
        accountsToCreate = accountsToCreate,
        csvImportId = csvImport.id,
        firstRowByAccountName = firstRowByAccountName,
        shadowSource = strategy.shadowSource,
    )

    // No auto-capture of template/regex/exact mappings: the strategy re-derives the target account
    // name on every import, and a later rename is resolved via audit history (getPreviousAccountNames),
    // so these would be redundant (and, stored globally, would leak across strategies). Only explicit
    // "map to existing account" selections are persisted (above), since those are not re-derivable.

    // Handle case when all rows are already processed (rows is already filtered by the caller)
    if (rows.isEmpty()) {
        logger.i { "No rows to process - all rows already imported" }
        return CsvImportResult(successCount = 0, failedRows = emptyList())
    }

    // An interactive preview may use different settings from the final import. The unattended path just
    // prepared these rows with the final settings, so it only needs to remap when account resolution changed.
    val reusePreparation = visibleAccounts != null && accountsToCreate.isEmpty() && selectedMappingsToPersist.isEmpty()
    val updatedAccounts = if (accountsToCreate.isEmpty()) knownAccounts else accountRepository.accountsVisibleTo(strategy)
    val accountsByName = updatedAccounts.associateBy { it.name }
    val mapperFor: ((String) -> CsvTransferMapper)? =
        if (reusePreparation && foreignFiles.isEmpty()) {
            null
        } else {
            val currenciesById = currencies.associateBy { it.id }
            val currenciesByCode = currencies.associateBy { it.code.uppercase() }
            val cryptoByCode = cryptoAssets.associateBy { it.code.uppercase() }
            val latestAccountMappings = accountMappingRepository.getAllMappings().first()
            val historicalAccountNames = accountRepository.getPreviousAccountNames()
            val accountOwnerNames = accountRepository.getAccountOwnerNames()
            // Rules see the name of the file each row came from, including rows from other staged files.
            val buildMapper: (String) -> CsvTransferMapper = { fileName ->
                CsvTransferMapper(
                    strategy = strategy,
                    columns = columns,
                    existingAccounts = accountsByName,
                    existingCurrencies = currenciesById,
                    existingCurrenciesByCode = currenciesByCode,
                    existingCryptoByCode = cryptoByCode,
                    assetAliases = assetAliases,
                    accountMappings = latestAccountMappings,
                    historicalAccountNames = historicalAccountNames,
                    sourceAccountOverride = selectedSourceAccountId,
                    passThroughDetector = passThroughAccounts.takeIf { it.isNotEmpty() }?.let { PassThroughDetector(it) },
                    attributeAccountMatchers = attributeAccountMatchers,
                    fileName = fileName,
                    accountOwnerNames = accountOwnerNames,
                )
            }
            buildMapper
        }
    val preparation = if (reusePreparation) basePrep else checkNotNull(mapperFor)(csvImport.originalFileName).prepareImport(rows)
    val mappedPrep = preparation.withUnresolvedAccountsAsErrors()
    // Each account a row charges a fee to pays it into its own fee account (per-row fees are their own movements, linked to
    // the main transfer), and so does a row that is itself a fee (its `Fee` is all of its amount). The engine resolves them.
    val feeAccountOf: Map<AccountId, AccountId> =
        importEngine.feeAccounts(
            mappedPrep.validTransfers.flatMapTo(mutableSetOf()) { row ->
                listOfNotNull(row.feePayerId().takeIf { row.feeAmount != null }, row.ownerOfFeeAccountSide())
            },
            Source.Csv(csvImport.id),
            shadowSource = strategy.shadowSource,
        )
    val finalPrep = mappedPrep.copy(validTransfers = mappedPrep.validTransfers.map { it.withOwnFeeAccount(feeAccountOf) })
    // The other staged files' legs of a cross-file trade group, mapped with the accounts this file just resolved.
    val foreignLegs: List<CsvTransferWithAttributes> =
        if (foreignFiles.isEmpty()) {
            emptyList()
        } else {
            val foreignMapper = checkNotNull(mapperFor)
            val crossFileRules =
                strategy.config.legGroups
                    .withIndex()
                    .filter { (_, rule) ->
                        rule.isCrossFileTrade()
                    }.map { it.index }
                    .toSet()
            foreignFiles
                .flatMap { file ->
                    foreignMapper(file.fileName).prepareImport(file.rows).validTransfers.filter { it.groupLeg?.ruleIndex in crossFileRules }
                }.distinctForeignLegs(finalPrep.validTransfers, strategy.config.uniqueIdentifierAttributeTypes().toSet())
        }
    val validCount = finalPrep.validTransfers.size
    val errorCount = finalPrep.errorRows.size
    logger.i { "Prepared $validCount valid transfers, $errorCount error rows" }

    // Mark mapping errors as ERROR status in database and save error messages. Skipped rows are settled
    // too, so they never count as pending again.
    importEngine.applyCsvImportMutations(
        listOf(
            CsvImportMutation.UpdateRowStatuses(
                csvImport.id,
                ImportStatus.ERROR.name,
                finalPrep.errorRows.map { it.rowIndex },
            ),
            CsvImportMutation.SaveErrors(csvImport.id, finalPrep.errorRows.associate { it.rowIndex to it.errorMessage }),
        ) + finalPrep.skippedRowMutations(csvImport.id),
    )

    // Pre-resolve attribute types. The unidentified-counterparty marker is engine-facing rather than a
    // mapped column, and is resolved for every import: even a file that identifies both ends of every row
    // needs it, to recognise (and supersede) the placeholder legs an earlier export left behind.
    // Every mapped type is resolved, not only those some row carries: the strategy owns them all, and a
    // re-import must be able to remove one a row no longer reports (e.g. `excluded` on an un-deleted row).
    val ownedAttributeValues = strategy.config.ownedAttributeValues()
    val allAttributeTypeNames =
        finalPrep.validTransfers
            .flatMap { it.attributes }
            .map { it.first }
            .toSet() + ownedAttributeValues.keys + WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME
    val attributeTypeIdByName = importEngine.getOrCreateAttributeTypes(allAttributeTypeNames.toList())
    val unidentifiedCounterpartyTypeId = attributeTypeIdByName[WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME]
    // The values each owned type can take (see ownedAttributeValues): a re-import only removes an owned
    // value it could have written.
    val ownedAttributes: Map<AttributeTypeId, Set<String>?> =
        ownedAttributeValues
            .mapNotNull { (name, values) -> attributeTypeIdByName[name]?.let { it to values } }
            .toMap()

    logger.i { "Starting to import $validCount transfers" }

    // Convert attributes from (typeName, value) to NewAttribute
    fun attributesFor(attributes: List<Pair<String, String>>): List<NewAttribute> =
        attributes.mapNotNull { (typeName, value) ->
            val typeId = attributeTypeIdByName[typeName]
            if (typeId != null) NewAttribute(typeId, value) else null
        }

    // Build the unified import batch. Accounts were already resolved/created
    // above, so transfers carry Existing refs and the central engine only
    // dedupes, writes transfers, applies updates and records sources.
    val uniqueIdTypeNames = strategy.config.uniqueIdentifierAttributeTypes().toSet()

    // Sources that split one trade across several rows (Binance stamps every partial fill of both legs
    // with the same second) are assembled here: each resolvable group folds into one trade and its legs
    // drop out of the transfer list. A group that does not resolve assembles to null and its rows stay
    // ordinary transfers, so nothing is ever dropped for want of a clean pairing.
    val assembledTrades = assembleCsvTradeGroups(strategy, finalPrep.validTransfers + foreignLegs)
    val assembledRowIndexes: Set<Long> = assembledTrades.flatMapTo(mutableSetOf()) { it.group.rowIndexes }
    // A leg whose partner is in a file not staged yet waits: booking it alone would count the exchange twice once
    // the partner arrives and the trade is booked.
    val crossFileRuleIndexes =
        strategy.config.legGroups
            .withIndex()
            .filter { (_, rule) -> rule.isCrossFileTrade() }
            .mapTo(mutableSetOf()) { it.index }
    val unpairedLegs =
        finalPrep.validTransfers.filter {
            it.groupLeg?.ruleIndex in crossFileRuleIndexes &&
                it.rowIndex !in assembledRowIndexes
        }
    // A rule that names an asset books its unpaired leg against it (see below); any other leg waits.
    val placeholderLegs = unpairedLegs.filter { strategy.unpairedAssetOf(it) != null && it.rowIndex in unprocessedRowIndexes }
    val heldBackRowIndexes: Set<Long> = unpairedLegs.mapTo(mutableSetOf()) { it.rowIndex }
    val manualEntryTypeId =
        placeholderLegs
            .takeIf { it.isNotEmpty() }
            ?.let {
                importEngine.getOrCreateAttributeTypes(
                    listOf(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME),
                )
            }?.get(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME)
    val placeholderTrades =
        manualEntryTypeId?.let { unpairedLegTrades(placeholderLegs, strategy, csvImport.id, importEngine, it) }.orEmpty()
    val supersedingExclusions =
        supersededPlaceholders(assembledTrades, strategy, tradeRepository, AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID))

    // Conversion groups another source already recorded as trades (Binance's dust API reports the same
    // sweep the export splits into unpairable legs). They are matched and dropped as whole groups: see
    // ConversionGroupReconciler for why only the debited legs can be compared, and why a partial match
    // must not suppress anything.
    val reconciledConversionRows: Map<Long, TradeId> =
        reconcileConversionGroups(strategy, finalPrep.validTransfers, tradeRepository)
    val assembledTradeRowIndexes: Map<LocalTradeKey, List<Long>> =
        assembledTrades.associate { assembled ->
            assembled.tradeKey(csvImport.id) to assembled.group.ownRowIndexes
        }

    // Rows carrying a credited leg (Currency != To Currency) are cross-asset conversions → trades.
    val importTrades =
        finalPrep.validTransfers.mapNotNull { row ->
            val credit = row.tradeTo ?: return@mapNotNull null
            ImportTradeIntent(
                key = LocalTradeKey("$CSV_TRADE_KEY_PREFIX${csvImport.id.id}-${row.rowIndex}"),
                source = Source.Csv(csvImport.id),
                timestamp = row.transfer.timestamp,
                description = row.transfer.description,
                fromAccountId = row.transfer.sourceAccountId,
                fromAmount = row.transfer.amount,
                toAccountId = row.transfer.targetAccountId,
                toAmount = credit,
                attributes = attributesFor(row.attributes),
                ownedAttributes = ownedAttributes,
                owner = ObservationOwnerKey.Record(ImportRecordKey.CsvRow(csvImport.id, row.rowIndex)),
            )
        } +
            assembledTrades.map { assembled ->
                ImportTradeIntent(
                    key = assembled.tradeKey(csvImport.id),
                    source = Source.Csv(csvImport.id),
                    timestamp = assembled.timestamp,
                    description = assembled.description,
                    fromAccountId = assembled.ownerAccountId,
                    fromAmount = assembled.fromAmount,
                    toAccountId = assembled.ownerAccountId,
                    toAmount = assembled.toAmount,
                    owner = ObservationOwnerKey.Group(assembled.groupKey(strategy), assembled.memberKeys(csvImport.id, foreignFiles)),
                )
            } + placeholderTrades
    // A leg of a trade this file books (assembled, or a placeholder) is part of it, but the trade carries no fee, so its fee goes
    // the same way. Only this file's own legs: the other files' are theirs.
    val tradeLegRows: Set<Long> =
        (assembledRowIndexes + placeholderLegs.map { it.rowIndex }).filterTo(mutableSetOf()) { isOwnRow(it) && it in unprocessedRowIndexes }

    // A trade carries no fee field, so a conversion row that also has a fee would otherwise drop it.
    // Emit each such fee as its own standalone movement (own account -> its fee account) so the
    // money isn't lost (e.g. Koinly trades). It carries the row's unique-id attributes, or re-importing
    // an overlapping export would book the fee again: the trade itself dedupes on its tuple, but a
    // key-less transfer never dedupes under a unique-id policy.
    val tradeFeeTransfers =
        finalPrep.validTransfers.mapNotNull { row ->
            if (row.tradeTo == null && row.rowIndex !in tradeLegRows) return@mapNotNull null
            val fee = row.feeAmount ?: return@mapNotNull null
            val payer = row.feePayerId()
            val uniqueAttributes = row.attributes.filter { (name, _) -> name in uniqueIdTypeNames }
            // Excluded with its trade (e.g. a row the source marks deleted).
            val exclusion = attributesFor(row.attributes).filter { it.typeId.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }
            ImportTransfer(
                rowKey = ImportRowKey.CsvRow(row.rowIndex),
                fromAccount = AccountRef.Existing(payer),
                toAccount = AccountRef.Existing(feeAccountOf.getValue(payer)),
                source = Source.Csv(csvImport.id),
                timestamp = row.transfer.timestamp,
                description = row.feeDescription ?: "${row.transfer.description} (fee)",
                amount = fee,
                attributes = attributesFor(uniqueAttributes) + exclusion,
                ownedAttributes = ownedAttributes.filterKeys { it.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID },
                uniqueKey = uniqueAttributes.takeIf { uniqueIdTypeNames.isNotEmpty() }?.toMap(),
                observationSlot = ObservationSlot.FEE,
            )
        }

    // Leg-group linking for `ThroughAccount` rules. Without `fundingWhen`, pair each debit leg to a credit
    // leg (same pairing key, nearest timestamp within the configured window) — both 1:1 swaps and
    // N-debits -> 1-credit dust events (many debits share the one credit). With it, each funding leg pairs
    // one to one with the nearest non-funding leg on the other side and is the link's first transfer.
    // Legs with no in-window partner are left unlinked (still valid, balance-correct).
    val conversionLinkByRow: Map<Long, BatchRelationship> =
        strategy.config.legGroups
            .withIndex()
            .flatMap { (index, rule) ->
                val legs = finalPrep.validTransfers.filter { it.groupLeg?.ruleIndex == index }
                when (val assembly = rule.assembly) {
                    is LegAssembly.Trade -> {
                        emptyList()
                    }

                    is LegAssembly.Linked -> {
                        linkMembersToAnchors(legs, assembly.relationshipTypeName)
                    }

                    is LegAssembly.ThroughAccount -> {
                        if (assembly.fundingWhen.isEmpty()) {
                            pairDebitsToCredits(legs, rule.windowSeconds.seconds, assembly.relationshipTypeName)
                        } else {
                            pairFundingLegs(legs, rule.windowSeconds.seconds, assembly.relationshipTypeName)
                        }
                    }
                }
            }.toMap()

    val importTransfers =
        finalPrep.validTransfers
            .filter {
                it.tradeTo == null &&
                    it.rowIndex !in assembledRowIndexes &&
                    it.rowIndex !in reconciledConversionRows &&
                    it.rowIndex !in heldBackRowIndexes &&
                    it.rowIndex in unprocessedRowIndexes
            }.map { row ->
                val uniqueKey =
                    if (uniqueIdTypeNames.isEmpty()) {
                        null
                    } else {
                        row.attributes
                            .filter { (name, _) -> name in uniqueIdTypeNames }
                            .associate { (name, value) -> name to value }
                    }
                val fee =
                    row.feeAmount?.let { feeMoney ->
                        ImportFee(
                            source = AccountRef.Existing(row.feePayerId()),
                            target = AccountRef.Existing(feeAccountOf.getValue(row.feePayerId())),
                            amount = feeMoney,
                            description = row.feeDescription ?: "Fee",
                            relationshipTypeId = RelationshipTypeId(WellKnownIds.FEE_RELATIONSHIP_TYPE_ID),
                        )
                    }
                // Pass-through (conduit) row: the mapper already routed the transfer's conduit side — the
                // target for an outgoing charge, the source for an incoming refund/cancellation; that side
                // is the chain's first conduit. Resolve the remaining chain conduits + the merchant account
                // the mapper created and let the engine add the spend legs (C1 -> C2, …, Cn -> merchant, or
                // reversed when incoming). accountsByName carries the created conduit + merchant ids.
                val passThrough =
                    row.passThrough?.let { pt ->
                        val merchantId = pt.merchantAccountId ?: accountsByName[pt.merchantName]?.id
                        val firstConduitId = if (pt.incoming) row.transfer.sourceAccountId else row.transfer.targetAccountId
                        val innerConduitIds = pt.conduitNames.drop(1).map { accountsByName[it]?.id }
                        if (merchantId == null || innerConduitIds.any { it == null }) {
                            null
                        } else {
                            val nodes = listOf(firstConduitId) + innerConduitIds.filterNotNull() + merchantId
                            collapsePassThroughChain(nodes, pt.spendDescriptions)?.let { (keptNodes, keptDescriptions) ->
                                ImportPassThrough(
                                    conduits = keptNodes.dropLast(1).map { AccountRef.Existing(it) },
                                    merchantTarget = AccountRef.Existing(keptNodes.last()),
                                    amount = row.transfer.amount,
                                    spendDescriptions = keptDescriptions,
                                    relationshipTypeId = RelationshipTypeId(pt.relationshipTypeId),
                                    incoming = pt.incoming,
                                )
                            }
                        }
                    }
                // Funding reconcile hint: match the row's funding value against the strategy's funding
                // attribute type to find the account that must hold the matching funding leg (e.g. Curve's
                // "7721" -> the Crypto.com Card account, via the `card-last4` attribute regexes). Never point
                // at the row's own source conduit (a self-reconcile makes no sense).
                val fundingMatcher = strategy.config.fundingAttributeMatch?.let { attributeAccountMatchers[it.attributeTypeName] }
                val fundingAccountId =
                    row.fundingMatchValue
                        ?.let { fundingMatcher?.match(it) }
                        ?.takeIf { it != row.transfer.sourceAccountId }
                ImportTransfer(
                    rowKey = ImportRowKey.CsvRow(row.rowIndex),
                    fromAccount = AccountRef.Existing(row.transfer.sourceAccountId),
                    toAccount = AccountRef.Existing(row.transfer.targetAccountId),
                    source = Source.Csv(csvImport.id),
                    statementAccount = row.statementAccountId?.takeIf { it != UNRESOLVED_ACCOUNT_ID }?.let { AccountRef.Existing(it) },
                    timestamp = row.transfer.timestamp,
                    description = row.transfer.description,
                    amount = row.transfer.amount,
                    attributes =
                        attributesFor(row.attributes) +
                            listOfNotNull(
                                // Preserve the stand-in's end so the matcher and materializer can replace it
                                // with the named end of another observation.
                                unidentifiedCounterpartyTypeId
                                    ?.takeIf { row.unidentifiedCounterpartyAccountId != null }
                                    ?.let {
                                        NewAttribute(
                                            it,
                                            if (row.unidentifiedCounterpartyAccountId == row.transfer.sourceAccountId) {
                                                WellKnownIds.UNIDENTIFIED_SOURCE
                                            } else {
                                                WellKnownIds.UNIDENTIFIED_TARGET
                                            },
                                        )
                                    },
                            ),
                    uniqueKey = uniqueKey,
                    ownedAttributes = ownedAttributes,
                    fee = fee,
                    passThrough = passThrough,
                    conversion = row.conversion,
                    batchRelationships = listOfNotNull(conversionLinkByRow[row.rowIndex]),
                    reconcileFundingAccountId = fundingAccountId,
                    unidentifiedCounterpartyAccountId = row.unidentifiedCounterpartyAccountId,
                )
            }

    // Personal counterparties (resolved via person-flagged account namings in the strategy's
    // rules) become People with an ownership link to their counterparty account, in
    // addition to the account itself. The engine dedups people by name key and ownerships by
    // (person, account), so repeats across rows/files and re-imports collapse. Mirrors the QIF importer,
    // which reuses this same CsvTransferMapper-computed signal.
    //
    // The counterparty account id is read from the resolved transfer (the side that isn't the source
    // account) rather than by name lookup, so a counterparty that resolved to an existing account with
    // different casing/name still gets its Person + ownership. The value is paired with the first CSV
    // row that referenced the person, so the person's audit source links back to that exact row.
    val personLinks = LinkedHashMap<String, Pair<AccountId, Long>>()
    finalPrep.validTransfers.forEach { row ->
        val name = row.personalCounterpartyName?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
        val counterpartyAccountId =
            when (selectedSourceAccountId) {
                row.transfer.sourceAccountId -> row.transfer.targetAccountId
                row.transfer.targetAccountId -> row.transfer.sourceAccountId
                else -> accountsByName[name]?.id
            } ?: return@forEach
        val previous = personLinks[name]
        personLinks[name] =
            (previous?.first ?: counterpartyAccountId) to minOf(previous?.second ?: row.rowIndex, row.rowIndex)
    }
    val peopleToCreate =
        personLinks.map { (name, link) ->
            val parts = name.split(Regex("\\s+"))
            ImportPersonIntent(
                key = LocalPersonKey(name),
                match = PersonMatchKey.ByNameKey(normalizeNameKey(name)),
                firstName = parts.first(),
                lastName = parts.drop(1).joinToString(" ").ifBlank { null },
                source = Source.Csv(csvImport.id, link.second),
            )
        }
    val personOwnerships =
        personLinks.map { (name, link) ->
            ImportOwnershipIntent(
                personKey = LocalPersonKey(name),
                account = AccountRef.Existing(link.first),
                source = Source.Csv(csvImport.id, link.second),
            )
        }

    val batch =
        ImportBatch(
            transfers = importTransfers + tradeFeeTransfers,
            shadowSource = strategy.shadowSource,
            peopleToCreate = peopleToCreate,
            ownerships = personOwnerships,
            trades = importTrades,
            reportedBalances =
                finalPrep.validTransfers.mapNotNull { row ->
                    row.reportedBalance?.let {
                        ImportReportedBalance(
                            record = ImportRecordKey.CsvRow(csvImport.id, row.rowIndex),
                            account = AccountRef.Existing(row.feePayerId()),
                            timestamp = row.transfer.timestamp,
                            amount = it,
                        )
                    }
                },
            tradeDedupePolicy = strategy.tradeDedupePolicy(),
            dedupePolicy = strategy.dedupePolicy(unidentifiedCounterpartyTypeId),
            strategyId = strategy.id.toString(),
            uniqueKeyExtractor =
                if (uniqueIdTypeNames.isEmpty()) {
                    null
                } else {
                    ExistingUniqueKeyExtractor { transfer ->
                        transfer.attributes
                            .filter { it.attributeType.name in uniqueIdTypeNames }
                            .associate { it.attributeType.name to it.value }
                    }
                },
        )

    val importResult = importEngine.import(batch, onProgress = onProgress, batchSize = engineBatchSize)
    // After the trades exist, not with them: were the write to fail, the placeholder would stay counted and the next apply would
    // retire it, where excluding it first could leave neither it nor the real trade.
    if (supersedingExclusions.isNotEmpty()) importEngine.import(ImportBatch(tradeAttributeMutations = supersedingExclusions))

    // Write back per-row CSV statuses from the engine outcome.
    val importedStatuses = mutableSetOf<Long>()
    val duplicateStatuses = mutableSetOf<Long>()
    val updatedStatuses = mutableSetOf<Long>()
    for ((rowKey, outcome) in importResult.rowOutcomes) {
        val rowIndex = (rowKey as ImportRowKey.CsvRow).rowIndex
        when (outcome.status) {
            ImportStatus.IMPORTED -> importedStatuses += rowIndex
            ImportStatus.DUPLICATE -> duplicateStatuses += rowIndex
            ImportStatus.UPDATED -> updatedStatuses += rowIndex
            ImportStatus.ERROR, ImportStatus.SKIPPED -> Unit
        }
    }
    // Cross-asset conversion rows are imported as trades, which the engine reports via createdTradeIds
    // (keyed by LocalTradeKey), NOT via rowOutcomes. Mark those rows IMPORTED — or DUPLICATE when the
    // intent matched an existing identical trade (createTrade is idempotent, e.g. a re-import or the
    // same event arriving from another export) — and clear their errors too, otherwise a converted row
    // keeps a stale ERROR status and gets reprocessed on the next import. The key is
    // "csv-<importId>-<rowIndex>".
    val tradeKeyPrefix = "$CSV_TRADE_KEY_PREFIX${csvImport.id.id}-"

    fun LocalTradeKey.rowIndexOrNull(): Long? =
        if (value.startsWith(tradeKeyPrefix)) value.removePrefix(tradeKeyPrefix).toLongOrNull() else null

    // An assembled trade speaks for every leg of its group, not just the row its key names, so all of
    // them take the trade's outcome. A single-row conversion has no group and falls back to its own row.
    fun LocalTradeKey.rowIndexes(): List<Long> = assembledTradeRowIndexes[this] ?: listOfNotNull(rowIndexOrNull())

    val tradeRowsByStatus =
        importResult.createdTradeIds.keys
            .flatMap { key -> key.rowIndexes().map { key to it } }
            .groupBy(
                keySelector = { (key, _) ->
                    if (key in importResult.dedupedTradeKeys) ImportStatus.DUPLICATE else ImportStatus.IMPORTED
                },
                valueTransform = { (_, rowIndex) -> rowIndex },
            )

    // Conversion legs suppressed as already-recorded read as duplicates, rather than as rows that quietly produced
    // nothing.
    duplicateStatuses += reconciledConversionRows.keys

    val statusMutations = mutableListOf<CsvImportMutation>()
    if (importedStatuses.isNotEmpty()) {
        statusMutations += CsvImportMutation.UpdateRowStatuses(csvImport.id, ImportStatus.IMPORTED.name, importedStatuses)
        statusMutations += CsvImportMutation.ClearErrors(csvImport.id, importedStatuses.toList())
    }
    for ((status, rows) in tradeRowsByStatus) {
        statusMutations += CsvImportMutation.UpdateRowStatuses(csvImport.id, status.name, rows)
        statusMutations += CsvImportMutation.ClearErrors(csvImport.id, rows)
    }
    if (duplicateStatuses.isNotEmpty()) {
        statusMutations += CsvImportMutation.UpdateRowStatuses(csvImport.id, ImportStatus.DUPLICATE.name, duplicateStatuses)
        // A row retried from ERROR can resolve as DUPLICATE; clear its stale error like imported/updated do.
        statusMutations += CsvImportMutation.ClearErrors(csvImport.id, duplicateStatuses.toList())
    }
    if (updatedStatuses.isNotEmpty()) {
        statusMutations += CsvImportMutation.UpdateRowStatuses(csvImport.id, ImportStatus.UPDATED.name, updatedStatuses)
        statusMutations += CsvImportMutation.ClearErrors(csvImport.id, updatedStatuses.toList())
    }
    importEngine.applyCsvImportMutations(statusMutations)
    val newTradeCount = tradeRowsByStatus[ImportStatus.IMPORTED]?.size ?: 0
    val dedupedTradeCount = tradeRowsByStatus[ImportStatus.DUPLICATE]?.size ?: 0
    val successCount = importResult.transfersImported + importResult.updated + newTradeCount
    val duplicateCount = importResult.duplicates + dedupedTradeCount

    logger.i {
        "Transfer import complete: $successCount imported/updated, $duplicateCount duplicate(s)"
    }

    // Refresh materialized views so transfers are visible (skipped in bulk; refreshed once at the end)
    if (refreshViews) {
        logger.i { "Refreshing materialized views" }
        maintenance.refreshMaterializedViews()
    }

    if ((successCount + duplicateCount + finalPrep.skippedRowIndexes.size) > 0) {
        recordCsvApplication(importEngine, csvImport.id, strategy, selectedSourceAccountId)
    }

    logger.i { "Import completed successfully" }

    // The engine import is atomic: any engine failure throws to the caller. The only per-row failures
    // are mapping errors detected before the import, so surface those to the dialog.
    return CsvImportResult(
        successCount = successCount,
        failedRows = finalPrep.errorRows.map { CsvImportResult.FailedRow(it.rowIndex, it.errorMessage) },
        duplicateCount = duplicateCount,
    )
}
