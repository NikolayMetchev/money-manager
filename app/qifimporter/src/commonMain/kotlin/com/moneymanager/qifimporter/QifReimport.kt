package com.moneymanager.qifimporter

import com.moneymanager.csvimporter.BULK_ENGINE_BATCH_SIZE
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.BulkImportResult
import com.moneymanager.csvimporter.BulkProgressTracker
import com.moneymanager.csvimporter.CsvImportResult
import com.moneymanager.csvimporter.CsvReimportResult
import com.moneymanager.csvimporter.ReimportMerge
import com.moneymanager.csvimporter.ReimportPlan
import com.moneymanager.csvimporter.ReimportReversal
import com.moneymanager.csvimporter.ReimportSkipReason
import com.moneymanager.csvimporter.ReimportSkippedAccount
import com.moneymanager.csvimporter.ReimportValueUpdate
import com.moneymanager.csvimporter.applyReimportReversals
import com.moneymanager.csvimporter.computeReimportMerges
import com.moneymanager.csvimporter.computeReimportReversals
import com.moneymanager.csvimporter.computeReimportValueUpdates
import com.moneymanager.csvimporter.effectiveSourceFor
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
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.QifImportMutation
import kotlinx.coroutines.flow.first
import org.lighthousegames.logging.logging

private val logger = logging()

/** Number of in-place transfer updates applied per engine batch during QIF re-import. */
private const val QIF_REIMPORT_VALUE_UPDATE_CHUNK = 100

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
                reason = ReimportSkipReason.CONFLICTING_TARGETS,
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
                    reason = ReimportSkipReason.TRANSFERS_BETWEEN,
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
    valueUpdateChunkSize: Int = QIF_REIMPORT_VALUE_UPDATE_CHUNK,
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
                    reason = ReimportSkipReason.MERGE_FAILED,
                    detail = expected.message ?: "Merge failed",
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
    val deletedEmptyAccounts = deleteEmptyImportCreatedAccounts(qifImport, accountRepository, qifImportRepository, importEngine)

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
 * Applies the planned in-place transfer updates in chunks, each an engine batch of UPDATE intents plus
 * the matching record-status writeback to UPDATED (which persists both the status and the transfer id).
 * A failing chunk falls back to per-record updates so one bad record downgrades to a skip. Returns the
 * updates that were applied; failures are appended to [skipped].
 */
private suspend fun applyQifValueUpdates(
    valueUpdates: List<ReimportValueUpdate>,
    qifImport: QifImport,
    importEngine: ImportEngine,
    skipped: MutableList<ReimportSkippedAccount>,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    chunkSize: Int = QIF_REIMPORT_VALUE_UPDATE_CHUNK,
): List<ReimportValueUpdate> {
    if (valueUpdates.isEmpty()) return emptyList()

    fun batchFor(updates: List<ReimportValueUpdate>) =
        ImportBatch(
            transfers =
                updates.map { update ->
                    ImportTransfer(
                        source = Source.Qif(qifImport.id, update.rowIndex),
                        operation = ImportOperation.UPDATE,
                        existingId = update.transferId,
                        fromAccount = AccountRef.Existing(update.sourceAccountId),
                        toAccount = AccountRef.Existing(update.targetAccountId),
                        timestamp = update.newTimestamp,
                        description = update.newDescription,
                        amount = update.newAmount,
                    )
                },
            dedupePolicy = DedupePolicy.None,
            qifImportMutations =
                listOf(
                    QifImportMutation.UpdateRecordStatuses(
                        id = qifImport.id,
                        status = ImportStatus.UPDATED.name,
                        recordTransferMap = updates.associate { it.rowIndex to it.transferId },
                    ),
                ),
        )

    val total = valueUpdates.size
    val updated = mutableListOf<ReimportValueUpdate>()
    var done = 0
    onProgress?.invoke(ImportProgress("Updating transactions", fraction = 0f, processed = 0, total = total))
    for (chunk in valueUpdates.chunked(chunkSize.coerceAtLeast(1))) {
        try {
            importEngine.import(batchFor(chunk))
            updated += chunk
        } catch (expected: Exception) {
            logger.warn(expected) { "QIF re-import value update chunk failed, falling back to per-record updates" }
            for (update in chunk) {
                try {
                    importEngine.import(batchFor(listOf(update)))
                    updated += update
                } catch (expectedRowError: Exception) {
                    logger.warn(expectedRowError) {
                        "QIF re-import value update of record ${update.rowIndex} ('${update.description}') failed"
                    }
                    skipped +=
                        ReimportSkippedAccount(
                            accountId = null,
                            accountName = update.description,
                            reason = ReimportSkipReason.UPDATE_FAILED,
                            detail = expectedRowError.message ?: "Update failed",
                        )
                }
            }
        }
        done += chunk.size
        onProgress?.invoke(
            ImportProgress("Updating transactions", fraction = done.toFloat() / total, processed = done, total = total),
        )
    }
    return updated
}

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
 * Deletes accounts this import created that hold no transfers (merged-away duplicates are already gone —
 * this catches ones emptied without being merge targets). Returns the deleted names.
 */
private suspend fun deleteEmptyImportCreatedAccounts(
    qifImport: QifImport,
    accountRepository: AccountReadRepository,
    qifImportRepository: QifImportReadRepository,
    importEngine: ImportEngine,
): List<String> {
    val importCreated = qifImportRepository.getAccountsCreatedByImport(qifImport.id)
    val remainingById = accountRepository.getAllAccounts().first().associateBy { it.id }
    val candidates = importCreated.mapNotNull { remainingById[it] }
    // One batched membership check instead of a COUNT query per candidate account.
    val withTransfers = accountRepository.accountsWithTransfers(candidates.map { it.id })
    val emptyAccounts = candidates.filter { it.id !in withTransfers }
    if (emptyAccounts.isEmpty()) return emptyList()

    importEngine.import(
        ImportBatch.manualEdits(
            accounts =
                emptyAccounts.map { account ->
                    ImportAccountIntent(
                        key = LocalAccountKey("qif-reimport-delete-${account.id.id}"),
                        source = Source.Qif(qifImport.id),
                        operation = ImportOperation.DELETE,
                        existingId = account.id,
                    )
                },
        ),
    )
    return emptyAccounts.map { it.name }
}

/**
 * Summary of a bulk QIF re-import run across many already-imported files. [merges] and [skipped] carry
 * the actual duplicate-account consolidations performed (and the ones that could not be merged) across
 * all files, so the UI can show WHICH accounts merged — the detail the per-file preview shows.
 */
data class QifBulkReimportResult(
    override val filesImported: Int,
    override val transfersCreated: Int,
    override val duplicatesSkipped: Int,
    override val filesSkippedNoStrategy: Int,
    override val filesFailed: Int,
    val merges: List<ReimportMerge>,
    val reversals: List<ReimportReversal>,
    val valueUpdates: Int,
    val emptyAccountsDeleted: Int,
    val skipped: List<ReimportSkippedAccount>,
) : BulkImportResult {
    override fun toSummary(): String =
        buildString {
            append("Re-imported $filesImported file${if (filesImported == 1) "" else "s"}")
            append(" · $transfersCreated new")
            if (valueUpdates > 0) append(" · $valueUpdates updated")
            if (merges.isNotEmpty()) append(" · ${merges.size} account${if (merges.size == 1) "" else "s"} merged")
            if (reversals.isNotEmpty()) append(" · ${reversals.size} merge${if (reversals.size == 1) "" else "s"} reversed")
            if (emptyAccountsDeleted > 0) append(" · $emptyAccountsDeleted empty removed")
            if (duplicatesSkipped > 0) append(" · $duplicatesSkipped duplicates skipped")
            if (filesSkippedNoStrategy > 0) append(" · $filesSkippedNoStrategy skipped (no strategy)")
            if (skipped.isNotEmpty()) append(" · ${skipped.size} not merged/updated")
            if (filesFailed > 0) append(" · $filesFailed failed")
        }
}

/**
 * Re-imports every already-imported [imports] QIF file in one go: for each, resolves the strategy it
 * was last imported with ([QifImport.lastAppliedStrategyId], falling back to content-aware
 * auto-selection), then plans and executes a re-import so current strategy/mapping changes apply
 * retroactively. Files with no resolvable strategy are skipped and counted. Refreshes materialized views
 * once at the end. Mirrors [bulkApplyQif]; reports row-weighted run-wide progress via [onProgress].
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
): QifBulkReimportResult {
    val qifStrategies = strategies.qifCompatible()
    var filesImported = 0
    var transfers = 0
    var duplicates = 0
    var skippedNoStrategy = 0
    var failed = 0
    val merges = mutableListOf<ReimportMerge>()
    val reversals = mutableListOf<ReimportReversal>()
    var valueUpdates = 0
    var emptyAccountsDeleted = 0
    val skipped = mutableListOf<ReimportSkippedAccount>()

    val tracker = BulkProgressTracker(imports.map { it.recordCount }, onProgress)
    tracker.started()

    imports.forEachIndexed { index, qifImport ->
        tracker.fileStarted(index, qifImport.originalFileName)
        try {
            val count = qifImportRepository.countRecords(qifImport.id)
            val records = qifImportRepository.getImportRecords(qifImport.id, count.coerceAtLeast(1), 0)
            val rows = QifCsvAdapter.toRows(records)
            if (rows.isEmpty()) return@forEachIndexed
            // Prefer the strategy the file was last imported with; fall back to content-aware auto-selection.
            // QIF data has no currency, so stamp the chosen one onto the strategy (as the import path does).
            val matched =
                (
                    qifImport.lastAppliedStrategyId?.let { id -> qifStrategies.find { it.id == id } }
                        ?: qifStrategies.selectForQifContent(rows, QifCsvAdapter.columns)
                )?.withQifCurrency(currencyId)
            if (matched == null) {
                skippedNoStrategy++
                return@forEachIndexed
            }
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
                    onProgress = { tracker.phase(index, qifImport.originalFileName, it) },
                )
            val result =
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
                    onProgress = { tracker.phase(index, qifImport.originalFileName, it) },
                    refreshViews = false,
                )
            filesImported++
            transfers += result.importResult?.successCount ?: 0
            duplicates += result.importResult?.duplicateCount ?: 0
            merges += result.mergedAccounts
            reversals += result.reversedMerges
            valueUpdates += result.updatedRows.size
            emptyAccountsDeleted += result.deletedEmptyAccounts.size
            skipped += result.skipped
        } catch (expected: Exception) {
            logger.error(expected) { "Bulk QIF re-import failed for ${qifImport.originalFileName}: ${expected.message}" }
            failed++
        }
    }

    tracker.done()
    maintenance.refreshMaterializedViews()

    return QifBulkReimportResult(
        filesImported = filesImported,
        transfersCreated = transfers,
        duplicatesSkipped = duplicates,
        filesSkippedNoStrategy = skippedNoStrategy,
        filesFailed = failed,
        merges = merges,
        reversals = reversals,
        valueUpdates = valueUpdates,
        emptyAccountsDeleted = emptyAccountsDeleted,
        skipped = skipped,
    )
}
