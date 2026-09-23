package com.moneymanager.qifimporter

import com.moneymanager.csvimporter.BULK_ENGINE_BATCH_SIZE
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.BulkReimportFileOutcome
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.csvimporter.CsvImportResult
import com.moneymanager.csvimporter.CsvReimportResult
import com.moneymanager.csvimporter.REIMPORT_VALUE_UPDATE_CHUNK
import com.moneymanager.csvimporter.ReimportMerge
import com.moneymanager.csvimporter.ReimportPlan
import com.moneymanager.csvimporter.ReimportSkippedAccount
import com.moneymanager.csvimporter.ReimportValueUpdate
import com.moneymanager.csvimporter.applyReimportReversals
import com.moneymanager.csvimporter.applyReimportValueUpdates
import com.moneymanager.csvimporter.computeReimportMerges
import com.moneymanager.csvimporter.computeReimportReversals
import com.moneymanager.csvimporter.computeReimportValueUpdates
import com.moneymanager.csvimporter.effectiveSourceFor
import com.moneymanager.csvimporter.runBulkReimport
import com.moneymanager.csvimporter.skipDetail
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.AccountMergeRequest
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.QifImportMutation
import com.moneymanager.importengineapi.deleteEmptyImportCreatedAccounts
import kotlinx.coroutines.flow.first
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * Builds the read-only [ReimportPlan] for [qifImport] under [strategy]: which import-created accounts
 * the current account mappings consolidate away (merges), which detected duplicates cannot be merged
 * (skips), and which already-imported records now map to different transfer values and will be updated
 * in place (value updates). Unlike the CSV plan there is no pass-through rewrite path (QIF import has
 * no pass-through), so [ReimportPlan.rewrites] is always empty. Split records are excluded from value
 * updates because a split record has a single stored transfer id shared by all its rows, so an
 * individual split-transfer cannot be addressed for an in-place update.
 */
@Suppress("LongParameterList")
suspend fun planQifReimport(
    qifImport: QifImport,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    qifImportRepository: QifImportReadRepository,
    transactionRepository: TransactionReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
): ReimportPlan {
    onProgress?.invoke(ImportProgress("Loading records"))
    val recordCount = qifImportRepository.countRecords(qifImport.id)
    val records = qifImportRepository.getImportRecords(qifImport.id, recordCount.coerceAtLeast(1), 0)
    val allRows = QifCsvAdapter.toRows(records)
    if (allRows.isEmpty()) return ReimportPlan(emptyList(), emptyList())

    // A split record expands to N rows sharing one stored transfer id, so value-updates can't target an
    // individual split-transfer; exclude those records from the value-update diff (they still merge and,
    // when not-yet-imported/errored, re-import correctly).
    val splitRecordIndexes = records.filter { it.splits.isNotEmpty() }.map { it.recordIndex }.toSet()

    val accounts = accountRepository.getAllAccounts().first()
    val mappings = accountMappingRepository.getAllMappings().first()
    val historicalAccountNames = accountRepository.getPreviousAccountNames()
    val importCreated = qifImportRepository.getAccountsCreatedByImport(qifImport.id)
    val effectiveSource = effectiveSourceFor(strategy, sourceAccountOverride)

    fun prep(accountMappings: List<AccountMapping>) =
        buildMapper(strategy, accounts, currencies, accountMappings, effectiveSource, historicalAccountNames)
            .prepareImport(allRows)

    onProgress?.invoke(ImportProgress("Analyzing records (pass 1 of 2)"))
    val mappedPrep = prep(mappings)
    onProgress?.invoke(ImportProgress("Analyzing records (pass 2 of 2)"))
    val candidates = computeReimportMerges(prep(emptyList()), mappedPrep, importCreated)
    val accountsById = accounts.associateBy { it.id }
    // Reversal detection re-maps with the mappings but NO historical names (see computeReimportReversals):
    // a merged-away record's name is an audit alias of its survivor, which would otherwise mask a removed mapping.
    val mappedNoHistoryPrep = buildMapper(strategy, accounts, currencies, mappings, effectiveSource).prepareImport(allRows)
    val reversals =
        computeReimportReversals(
            importCreated = importCreated,
            mappedNoHistoryPrep = mappedNoHistoryPrep,
            accountsById = accountsById,
            accountRepository = accountRepository,
            transferSourceRepository = transferSourceRepository,
            rowIndexForSource = { source -> (source as? Source.Qif)?.takeIf { it.importId == qifImport.id }?.recordIndex },
            onProgress = onProgress,
        )
    val reversalRowIndexes = reversals.flatMapTo(mutableSetOf()) { it.rowIndexes }
    // One batched load instead of a per-record getTransactionById round trip in the value-update scan.
    onProgress?.invoke(ImportProgress("Loading existing transactions"))
    val existingTransfers = transactionRepository.getTransactionsByIds(allRows.mapNotNull { it.transferId })
    val valueUpdates =
        computeReimportValueUpdates(
            allRows = allRows,
            mappedPrep = mappedPrep,
            rewrittenRowIndexes = splitRecordIndexes + reversalRowIndexes,
            existingTransferLookup = { transferId -> existingTransfers[transferId] },
            onProgress = onProgress,
        )

    fun nameOf(id: AccountId): String = accountsById[id]?.name ?: "#${id.id}"

    val merges = mutableListOf<ReimportMerge>()
    val skipped = mutableListOf<ReimportSkippedAccount>()

    onProgress?.invoke(ImportProgress("Checking duplicate accounts"))
    for ((duplicate, targets) in candidates.conflicts) {
        skipped +=
            ReimportSkippedAccount(
                accountId = duplicate,
                accountName = nameOf(duplicate),
                detail = "Records map it to different accounts: ${targets.joinToString { nameOf(it) }}",
            )
    }
    for ((duplicate, target) in candidates.merges) {
        val between = accountRepository.getTransfersBetweenAccounts(duplicate, target)
        if (between.isNotEmpty()) {
            skipped +=
                ReimportSkippedAccount(
                    accountId = duplicate,
                    accountName = nameOf(duplicate),
                    detail = "${between.size} transaction(s) between '${nameOf(duplicate)}' and '${nameOf(target)}' — merge manually",
                )
            continue
        }
        merges +=
            ReimportMerge(
                duplicateId = duplicate,
                duplicateName = nameOf(duplicate),
                targetId = target,
                targetName = nameOf(target),
                transferCount = accountRepository.countTransfersByAccount(duplicate),
            )
    }
    return ReimportPlan(merges = merges, skipped = skipped, valueUpdates = valueUpdates, reversals = reversals)
}

/**
 * Executes a re-import of [qifImport] under [strategy] following a confirmed [plan], mirroring
 * `executeCsvReimport` minus the pass-through-rewrite step:
 * 1. updates each planned value-changed record's transfer in place — BEFORE the merges, while the
 *    persisted account ids the updates re-send still exist;
 * 2. merges each planned duplicate into its target (reversible via the account-merge history) — merging
 *    BEFORE re-running records so dedupe sees the moved transfers instead of importing them twice;
 * 3. re-runs the strategy over records never imported or errored, which now resolve via the new mappings;
 * 4. deletes import-created accounts left with no transfers;
 * 5. refreshes materialized views (unless [refreshViews] is false, so a bulk caller refreshes once).
 *
 * Returns the shared [CsvReimportResult]; [CsvReimportResult.rewrittenRows] is always empty for QIF.
 */
@Suppress("LongParameterList", "LongMethod")
suspend fun executeQifReimport(
    plan: ReimportPlan,
    qifImport: QifImport,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    qifImportRepository: QifImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    valueUpdateChunkSize: Int = REIMPORT_VALUE_UPDATE_CHUNK,
    refreshViews: Boolean = true,
): CsvReimportResult {
    val merged = mutableListOf<ReimportMerge>()
    val skipped = plan.skipped.toMutableList()

    // Reverse no-longer-valid merges FIRST (recreate split-out accounts + move transfers back).
    val reversedMerges = applyReimportReversals(plan.reversals, importEngine, skipped, onProgress)

    val updatedRows = applyQifValueUpdates(plan.valueUpdates, qifImport, importEngine, skipped, onProgress, valueUpdateChunkSize)

    // One batch per merge so a surprise failure downgrades to a per-account skip instead of aborting the
    // remaining merges.
    for ((index, merge) in plan.merges.withIndex()) {
        onProgress?.invoke(
            ImportProgress(
                "Merging accounts",
                fraction = index.toFloat() / plan.merges.size,
                processed = index,
                total = plan.merges.size,
            ),
        )
        try {
            importEngine.import(
                ImportBatch.manualEdits(
                    accountMerges = listOf(AccountMergeRequest(deletedId = merge.duplicateId, survivingId = merge.targetId)),
                ),
            )
            merged += merge
        } catch (expected: Exception) {
            logger.warn(expected) { "QIF re-import merge of '${merge.duplicateName}' into '${merge.targetName}' failed" }
            skipped +=
                ReimportSkippedAccount(
                    accountId = merge.duplicateId,
                    accountName = merge.duplicateName,
                    detail = skipDetail("Merge failed", expected.message),
                )
        }
    }

    val importResult =
        applyStagedQif(
            qifImport = qifImport,
            strategy = strategy,
            sourceAccountOverride = sourceAccountOverride,
            currencies = currencies,
            accountMappingRepository = accountMappingRepository,
            accountRepository = accountRepository,
            qifImportRepository = qifImportRepository,
            maintenance = maintenance,
            importEngine = importEngine,
            onProgress = onProgress,
            engineBatchSize = BULK_ENGINE_BATCH_SIZE,
        )

    onProgress?.invoke(ImportProgress("Cleaning up empty accounts"))
    val deletedEmptyAccounts =
        importEngine.deleteEmptyImportCreatedAccounts(
            createdAccountIds = qifImportRepository.getAccountsCreatedByImport(qifImport.id),
            source = Source.Qif(qifImport.id),
            accountRepository = accountRepository,
            // QIF import never creates trades, so there is no trade-only account to protect here.
            tradeRepository = null,
            keyPrefix = "qif-reimport-delete",
        )

    if (refreshViews) {
        onProgress?.invoke(ImportProgress("Refreshing views"))
        maintenance.refreshMaterializedViews()
    }

    return CsvReimportResult(
        mergedAccounts = merged,
        skipped = skipped,
        deletedEmptyAccounts = deletedEmptyAccounts,
        // Re-express the QIF run outcome as the shared CsvImportResult the result type carries; only the
        // success/duplicate counts are consumed downstream (QIF per-record errors aren't row-detailed).
        importResult =
            importResult?.let {
                CsvImportResult(successCount = it.successCount, failedRows = emptyList(), duplicateCount = it.duplicateCount)
            },
        updatedRows = updatedRows,
        reversedMerges = reversedMerges,
    )
}

/**
 * Applies the planned in-place transfer updates through the shared [applyReimportValueUpdates], pairing
 * each chunk's UPDATE intents with this import's record-status writeback to UPDATED (which persists both
 * the status and the transfer id).
 */
private suspend fun applyQifValueUpdates(
    valueUpdates: List<ReimportValueUpdate>,
    qifImport: QifImport,
    importEngine: ImportEngine,
    skipped: MutableList<ReimportSkippedAccount>,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    chunkSize: Int = REIMPORT_VALUE_UPDATE_CHUNK,
): List<ReimportValueUpdate> =
    applyReimportValueUpdates(
        valueUpdates = valueUpdates,
        importEngine = importEngine,
        skipped = skipped,
        sourceFor = { rowIndex -> Source.Qif(qifImport.id, rowIndex) },
        batchFor = { transfers, recordTransferMap ->
            ImportBatch(
                transfers = transfers,
                dedupePolicy = DedupePolicy.None,
                qifImportMutations =
                    listOf(
                        QifImportMutation.UpdateRecordStatuses(
                            id = qifImport.id,
                            status = ImportStatus.UPDATED.name,
                            recordTransferMap = recordTransferMap,
                        ),
                    ),
            )
        },
        logLabel = "QIF re-import",
        rowLabel = "record",
        onProgress = onProgress,
        chunkSize = chunkSize,
    )

/**
 * Re-runs [strategy] over only the [qifImport] records that are not yet imported or errored (unlike
 * [runImport], which re-runs everything), so a re-import imports the remaining records under the current
 * mappings without reprocessing already-imported ones. Returns null when there are none. Because all of
 * a split record's rows share the record's status, a re-imported split record's rows are filtered out
 * together — never half-re-run.
 */
@Suppress("LongParameterList")
private suspend fun applyStagedQif(
    qifImport: QifImport,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    qifImportRepository: QifImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    engineBatchSize: Int = Int.MAX_VALUE,
): QifImportResult? {
    val recordCount = qifImportRepository.countRecords(qifImport.id)
    val records = qifImportRepository.getImportRecords(qifImport.id, recordCount.coerceAtLeast(1), 0)
    val rows =
        QifCsvAdapter
            .toRows(records)
            .filter { it.importStatus == null || it.importStatus == ImportStatus.ERROR }
    if (rows.isEmpty()) return null

    val effectiveSource = effectiveSourceFor(strategy, sourceAccountOverride)
    val accounts = accountRepository.getAllAccounts().first()
    val mappings = accountMappingRepository.getAllMappings().first()
    val historicalAccountNames = accountRepository.getPreviousAccountNames()
    val basePrep =
        buildMapper(strategy, accounts, currencies, mappings, effectiveSource, historicalAccountNames).prepareImport(rows)

    return runImport(
        qifImport = qifImport,
        rows = rows,
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
        refreshViews = false,
        onProgress = onProgress,
        engineBatchSize = engineBatchSize,
    )
}

/**
 * Re-imports every already-imported [imports] QIF file in one go: for each, resolves the strategy it
 * was last imported with ([QifImport.lastAppliedStrategyId], falling back to content-aware
 * auto-selection), then plans and executes a re-import so current strategy/mapping changes apply
 * retroactively. Files with no resolvable strategy are skipped and counted. Refreshes materialized views
 * once at the end. Mirrors [bulkApplyQif]; reports row-weighted run-wide progress via [onProgress].
 */
@Suppress("LongParameterList")
suspend fun bulkReimportQif(
    imports: List<QifImport>,
    sourceAccountOverride: AccountId?,
    currencyId: CurrencyId?,
    strategies: List<CsvImportStrategy>,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    qifImportRepository: QifImportReadRepository,
    transactionRepository: TransactionReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: suspend (BulkImportProgress) -> Unit,
): BulkReimportResult {
    val qifStrategies = strategies.qifCompatible()
    return runBulkReimport(
        imports = imports,
        rowCount = { it.recordCount },
        fileName = { it.originalFileName },
        logLabel = "QIF",
        maintenance = maintenance,
        onProgress = onProgress,
        prepare = {},
    ) { _, qifImport, _, onFileProgress ->
        val count = qifImportRepository.countRecords(qifImport.id)
        val records = qifImportRepository.getImportRecords(qifImport.id, count.coerceAtLeast(1), 0)
        val rows = QifCsvAdapter.toRows(records)
        // Prefer the strategy the file was last imported with; fall back to content-aware auto-selection.
        // QIF data has no currency, so stamp the chosen one onto the strategy (as the import path does).
        val matched =
            if (rows.isEmpty()) {
                null
            } else {
                (
                    qifImport.lastAppliedStrategyId?.let { id -> qifStrategies.find { it.id == id } }
                        ?: qifStrategies.selectForQifContent(rows, QifCsvAdapter.columns)
                )?.withQifCurrency(currencyId)
            }
        when {
            // An empty file has nothing to re-import, and (unlike a missing strategy) is not counted.
            rows.isEmpty() -> BulkReimportFileOutcome.NoWork
            matched == null -> BulkReimportFileOutcome.NoStrategy
            else -> {
                val plan =
                    planQifReimport(
                        qifImport = qifImport,
                        strategy = matched,
                        sourceAccountOverride = sourceAccountOverride,
                        currencies = currencies,
                        accountMappingRepository = accountMappingRepository,
                        accountRepository = accountRepository,
                        qifImportRepository = qifImportRepository,
                        transactionRepository = transactionRepository,
                        transferSourceRepository = transferSourceRepository,
                        onProgress = onFileProgress,
                    )
                BulkReimportFileOutcome.Reimported(
                    executeQifReimport(
                        plan = plan,
                        qifImport = qifImport,
                        strategy = matched,
                        sourceAccountOverride = sourceAccountOverride,
                        currencies = currencies,
                        accountMappingRepository = accountMappingRepository,
                        accountRepository = accountRepository,
                        qifImportRepository = qifImportRepository,
                        maintenance = maintenance,
                        importEngine = importEngine,
                        onProgress = onFileProgress,
                        refreshViews = false,
                    ),
                )
            }
        }
    }
}
