@file:OptIn(ExperimentalTime::class)

package com.moneymanager.csvimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.importengineapi.AccountMergeRequest
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import kotlinx.coroutines.flow.first
import org.lighthousegames.logging.logging
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val logger = logging()

/** How often the per-row plan-phase scans report progress. */
private const val PLAN_PROGRESS_EVERY_ROWS = 25

/** Engine write-chunk size for the re-run of remaining rows, so it reports per-chunk progress. */
private const val REIMPORT_ENGINE_BATCH_SIZE = 250

/** Default number of in-place transfer updates applied per engine batch. */
internal const val REIMPORT_VALUE_UPDATE_CHUNK = 100

/** Why a duplicate account detected during re-import was not merged. */
enum class ReimportSkipReason {
    /** Different rows of the import resolve the duplicate to different target accounts. */
    CONFLICTING_TARGETS,

    /** Transfers exist between the duplicate and its target, which a merge cannot represent. */
    TRANSFERS_BETWEEN,

    /** The merge itself failed when executed (e.g. a concurrent write changed the accounts). */
    MERGE_FAILED,

    /** Deleting a row's old transfers for a pass-through rewrite failed; the row was left as-is. */
    REWRITE_FAILED,

    /** Updating a row's transfer to its recomputed values failed; the transfer was left as-is. */
    UPDATE_FAILED,
}

/** One duplicate-account merge the re-import will perform (or performed). */
data class ReimportMerge(
    val duplicateId: AccountId,
    val duplicateName: String,
    val targetId: AccountId,
    val targetName: String,
    /** Transfers on the duplicate account that the merge moves to the target. */
    val transferCount: Long,
)

/**
 * Something the re-import detected but will not (or could not) act on: a duplicate account it cannot
 * merge, or a row rewrite that failed. [accountId] is null for row-level (rewrite) entries, where
 * [accountName] carries the row's description instead.
 */
data class ReimportSkippedAccount(
    val accountId: AccountId?,
    val accountName: String,
    val reason: ReimportSkipReason,
    val detail: String,
)

/**
 * One already-imported row the re-import will rewrite because the current pass-through configuration
 * routes it through a conduit chain its existing transfer does not reflect: the old transfer(s) are
 * deleted, the row's status is reset, and the strategy re-run imports it through the chain.
 */
data class ReimportRewrite(
    val rowIndex: Long,
    /** The row's existing transfer plus its linked fee/spend legs, all deleted before the re-run. */
    val transferIdsToDelete: List<TransferId>,
    /** The row's statement description, for the preview. */
    val description: String,
    /** The conduit chain (outermost first) the row will be routed through. */
    val conduitNames: List<String>,
    /** The clean merchant the final spend leg pays. */
    val merchantName: String,
)

/**
 * One already-imported row whose recomputed values under the current strategy differ from its
 * persisted transfer (e.g. the strategy's amount/currency columns changed): the transfer is updated
 * in place. Accounts are deliberately NOT part of the update — account changes are the domain of
 * merges (and pass-through rewrites), so the persisted accounts are re-sent unchanged.
 */
data class ReimportValueUpdate(
    val rowIndex: Long,
    val transferId: TransferId,
    /** The row's statement description, for the preview. */
    val description: String,
    /** Human-readable "field: old → new" lines, for the preview. */
    val changes: List<String>,
    val newTimestamp: Instant,
    val newDescription: String,
    val newAmount: Money,
    /** The persisted transfer's accounts, re-sent unchanged (the engine update requires them). */
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
)

/** The read-only preview of what a re-import would do, shown for confirmation before executing. */
data class ReimportPlan(
    val merges: List<ReimportMerge>,
    val skipped: List<ReimportSkippedAccount>,
    /** Already-imported rows to reroute through a pass-through conduit chain. */
    val rewrites: List<ReimportRewrite> = emptyList(),
    /** Already-imported rows whose transfer values (amount/currency/date/description) changed. */
    val valueUpdates: List<ReimportValueUpdate> = emptyList(),
) {
    val isEmpty: Boolean get() = merges.isEmpty() && skipped.isEmpty() && rewrites.isEmpty() && valueUpdates.isEmpty()
}

/** The outcome of an executed re-import. */
data class CsvReimportResult(
    val mergedAccounts: List<ReimportMerge>,
    val skipped: List<ReimportSkippedAccount>,
    /** Import-created accounts deleted because they ended up with no transfers. */
    val deletedEmptyAccounts: List<String>,
    /** Result of re-running the strategy over not-yet-imported/errored rows; null when there were none. */
    val importResult: CsvImportResult?,
    /** Rows rewritten through a pass-through conduit chain (old transfers deleted + row re-imported). */
    val rewrittenRows: List<ReimportRewrite> = emptyList(),
    /** Rows whose transfers were updated in place to the recomputed values. */
    val updatedRows: List<ReimportValueUpdate> = emptyList(),
)

/** Raw merge detection output: duplicate → single target, plus duplicates with competing targets. */
data class ReimportMergeCandidates(
    val merges: Map<AccountId, AccountId>,
    val conflicts: Map<AccountId, Set<AccountId>>,
)

/**
 * Detects which import-created accounts the current account mappings consolidate onto another account.
 *
 * [baselinePrep] is the import's rows mapped with NO persisted account mappings (each row resolves by
 * account name, i.e. to the duplicate the original import created); [mappedPrep] is the same rows
 * mapped with the current mappings. A row/side where the baseline resolves to an import-created
 * account and the mappings resolve to a different existing account marks that account as a duplicate
 * of the mapped target. A duplicate whose rows disagree on the target is reported as a conflict and
 * never merged partially.
 */
fun computeReimportMerges(
    baselinePrep: ImportPreparation,
    mappedPrep: ImportPreparation,
    importCreatedAccounts: Set<AccountId>,
): ReimportMergeCandidates {
    val mappedByRow = mappedPrep.validTransfers.associateBy { it.rowIndex }
    val targetsByDuplicate = mutableMapOf<AccountId, MutableSet<AccountId>>()

    fun collect(
        baselineId: AccountId,
        mappedId: AccountId,
    ) {
        if (baselineId !in importCreatedAccounts) return
        if (mappedId == baselineId || mappedId.id <= 0) return
        targetsByDuplicate.getOrPut(baselineId) { mutableSetOf() }.add(mappedId)
    }

    for (baseline in baselinePrep.validTransfers) {
        val mapped = mappedByRow[baseline.rowIndex] ?: continue
        collect(baseline.transfer.sourceAccountId, mapped.transfer.sourceAccountId)
        collect(baseline.transfer.targetAccountId, mapped.transfer.targetAccountId)
        // Pass-through rows carry the conduit on both transfer sides, so a mapping applied to the
        // stripped merchant name is only visible on the merchant account itself.
        val baselineMerchant = baseline.passThrough?.merchantAccountId
        val mappedMerchant = mapped.passThrough?.merchantAccountId
        if (baselineMerchant != null && mappedMerchant != null) collect(baselineMerchant, mappedMerchant)
    }

    return ReimportMergeCandidates(
        merges =
            targetsByDuplicate
                .filterValues { it.size == 1 }
                .mapValues { (_, targets) -> targets.single() },
        conflicts = targetsByDuplicate.filterValues { it.size > 1 },
    )
}

/**
 * Builds the read-only [ReimportPlan] for [csvImport] under [strategy]: which import-created accounts
 * the current mappings consolidate away (as merges), which detected duplicates cannot be merged,
 * which already-imported rows the current pass-through configuration reroutes through a conduit chain
 * (as rewrites), and which already-imported rows now map to different transfer values — a changed
 * amount/currency/date/description column — and will be updated in place (as value updates).
 * Safe to call from the UI to preview a re-import before [executeCsvReimport].
 * [onProgress] reports the plan phases (with per-row counts for the row scans) for a progress UI.
 */
@Suppress("LongParameterList")
suspend fun planCsvReimport(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    transactionRepository: TransactionReadRepository,
    relationshipRepository: TransferRelationshipReadRepository,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
): ReimportPlan {
    onProgress?.invoke(ImportProgress("Loading rows"))
    val allRows = csvImportRepository.getImportRows(csvImport.id, limit = csvImport.rowCount.coerceAtLeast(1), offset = 0)
    if (allRows.isEmpty()) return ReimportPlan(emptyList(), emptyList())

    val accounts = accountRepository.getAllAccounts().first()
    val mappings = accountMappingRepository.getAllMappings().first()
    val historicalAccountNames = accountRepository.getPreviousAccountNames()
    val importCreated = csvImportRepository.getAccountsCreatedByImport(csvImport.id)
    val effectiveSource = effectiveSourceFor(strategy, sourceAccountOverride)

    fun prep(accountMappings: List<AccountMapping>) =
        buildCsvMapper(
            strategy,
            csvImport.columns,
            accounts,
            currencies,
            accountMappings,
            effectiveSource,
            passThroughAccounts,
            historicalAccountNames,
        ).prepareImport(allRows)

    onProgress?.invoke(ImportProgress("Analyzing rows (pass 1 of 2)"))
    val mappedPrep = prep(mappings)
    onProgress?.invoke(ImportProgress("Analyzing rows (pass 2 of 2)"))
    val candidates = computeReimportMerges(prep(emptyList()), mappedPrep, importCreated)
    val rewrites =
        computeReimportRewrites(allRows, mappedPrep, relationshipRepository, onProgress) { transferId ->
            transactionRepository.getTransactionById(transferId.id).first()
        }
    val valueUpdates =
        computeReimportValueUpdates(
            allRows = allRows,
            mappedPrep = mappedPrep,
            rewrittenRowIndexes = rewrites.map { it.rowIndex }.toSet(),
            transactionRepository = transactionRepository,
            onProgress = onProgress,
        )

    val accountsById = accounts.associateBy { it.id }

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
                detail = "Rows map it to different accounts: ${targets.joinToString { nameOf(it) }}",
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
    return ReimportPlan(merges = merges, skipped = skipped, rewrites = rewrites, valueUpdates = valueUpdates)
}

/**
 * Finds already-imported rows whose recomputed transfer values differ from the persisted transfer —
 * the strategy's amount/currency/date/description mappings changed since the row was imported. Only
 * value fields are compared; account differences are the domain of merges (and a persisted transfer
 * whose account was mapped away is matched pre-merge, so comparing accounts here would misfire).
 * Pass-through rows are excluded — value changes there invalidate every leg of the chain, so they
 * are handled by the rewrite path instead (see [computeReimportRewrites]).
 */
internal suspend fun computeReimportValueUpdates(
    allRows: List<CsvRow>,
    mappedPrep: ImportPreparation,
    rewrittenRowIndexes: Set<Long>,
    transactionRepository: TransactionReadRepository,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
): List<ReimportValueUpdate> {
    val rowsByIndex = allRows.associateBy { it.rowIndex }
    val updates = mutableListOf<ReimportValueUpdate>()
    val total = mappedPrep.validTransfers.size
    mappedPrep.validTransfers.forEachIndexed { index, mapped ->
        emitRowProgress(onProgress, "Checking rows for value changes", index, total)
        valueUpdateFor(mapped, rowsByIndex, rewrittenRowIndexes, transactionRepository)?.let { updates += it }
    }
    return updates
}

private suspend fun valueUpdateFor(
    mapped: CsvTransferWithAttributes,
    rowsByIndex: Map<Long, CsvRow>,
    rewrittenRowIndexes: Set<Long>,
    transactionRepository: TransactionReadRepository,
): ReimportValueUpdate? {
    if (mapped.passThrough != null || mapped.rowIndex in rewrittenRowIndexes) return null
    val row = rowsByIndex[mapped.rowIndex] ?: return null
    if (row.importStatus != ImportStatus.IMPORTED && row.importStatus != ImportStatus.UPDATED) return null
    val transferId = row.transferId ?: return null
    val existing = transactionRepository.getTransactionById(transferId.id).first() ?: return null
    val changes =
        buildList {
            if (mapped.transfer.amount != existing.amount) {
                add("amount ${existing.amount.display()} → ${mapped.transfer.amount.display()}")
            }
            if (mapped.transfer.timestamp != existing.timestamp) {
                add("date ${existing.timestamp} → ${mapped.transfer.timestamp}")
            }
            if (mapped.transfer.description != existing.description) {
                add("description '${existing.description}' → '${mapped.transfer.description}'")
            }
        }
    if (changes.isEmpty()) return null
    return ReimportValueUpdate(
        rowIndex = mapped.rowIndex,
        transferId = transferId,
        description = mapped.transfer.description,
        changes = changes,
        newTimestamp = mapped.transfer.timestamp,
        newDescription = mapped.transfer.description,
        newAmount = mapped.transfer.amount,
        sourceAccountId = existing.sourceAccountId,
        targetAccountId = existing.targetAccountId,
    )
}

private fun Money.display(): String = "${toDisplayValue()} ${currency.code}"

/** Emits a throttled per-row [ImportProgress] (every [PLAN_PROGRESS_EVERY_ROWS] rows and at the end). */
private suspend fun emitRowProgress(
    onProgress: (suspend (ImportProgress) -> Unit)?,
    detail: String,
    index: Int,
    total: Int,
) {
    if (onProgress == null) return
    val processed = index + 1
    if (processed % PLAN_PROGRESS_EVERY_ROWS == 0 || processed == total) {
        onProgress(ImportProgress(detail, fraction = processed.toFloat() / total, processed = processed, total = total))
    }
}

/**
 * Finds already-imported rows whose current preparation routes them through a pass-through conduit
 * chain their existing transfer does not reflect: imported before the definitions existed, with a
 * shorter chain (compared via the number of pass-through legs hanging off the row's transfer), or —
 * when the chain itself still matches — with transfer values (amount/currency/date/description) the
 * current strategy now computes differently, which invalidates every leg of the chain. Only IMPORTED
 * rows are considered — a DUPLICATE row's transfer belongs to another row and must not be deleted.
 * [existingTransferLookup] loads a row's persisted funding transfer for the value comparison; a null
 * result skips that comparison.
 */
internal suspend fun computeReimportRewrites(
    allRows: List<CsvRow>,
    mappedPrep: ImportPreparation,
    relationshipRepository: TransferRelationshipReadRepository,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    existingTransferLookup: suspend (TransferId) -> Transfer?,
): List<ReimportRewrite> {
    val rowsByIndex = allRows.associateBy { it.rowIndex }
    val rewrites = mutableListOf<ReimportRewrite>()
    val total = mappedPrep.validTransfers.size
    mappedPrep.validTransfers.forEachIndexed { index, mapped ->
        emitRowProgress(onProgress, "Checking pass-through rows", index, total)
        rewriteFor(mapped, rowsByIndex, relationshipRepository, existingTransferLookup)?.let { rewrites += it }
    }
    return rewrites
}

private suspend fun rewriteFor(
    mapped: CsvTransferWithAttributes,
    rowsByIndex: Map<Long, CsvRow>,
    relationshipRepository: TransferRelationshipReadRepository,
    existingTransferLookup: suspend (TransferId) -> Transfer?,
): ReimportRewrite? {
    val passThrough = mapped.passThrough ?: return null
    val row = rowsByIndex[mapped.rowIndex] ?: return null
    if (row.importStatus != ImportStatus.IMPORTED) return null
    val transferId = row.transferId ?: return null
    val linked = collectLinkedLegs(transferId, relationshipRepository)
    val chainChanged = linked.passThroughLegCount != passThrough.conduitNames.size
    // A value change (e.g. the strategy's amount/currency columns moved) must propagate to every
    // spend leg of the chain, so the row is rewritten rather than updated in place.
    val valuesChanged =
        !chainChanged &&
            existingTransferLookup(transferId)?.let { existing ->
                existing.amount != mapped.transfer.amount ||
                    existing.timestamp != mapped.transfer.timestamp ||
                    existing.description != mapped.transfer.description
            } == true
    if (!chainChanged && !valuesChanged) return null
    return ReimportRewrite(
        rowIndex = row.rowIndex,
        transferIdsToDelete = linked.transferIds,
        description = mapped.transfer.description,
        conduitNames = passThrough.conduitNames,
        merchantName = passThrough.merchantName,
    )
}

/** A row's transfer plus everything hanging off it via forward relationships. */
private class LinkedLegs(
    val transferIds: List<TransferId>,
    /** Number of `pass-through` links followed — the length of the persisted conduit chain. */
    val passThroughLegCount: Int,
)

/**
 * Collects [rootId] plus every transfer reachable through FORWARD relationships (this transfer as
 * id1): fee legs and pass-through spend legs, transitively. Reversal links are not followed — their
 * id2 is another row's leg and must never be deleted with this row.
 */
private suspend fun collectLinkedLegs(
    rootId: TransferId,
    relationshipRepository: TransferRelationshipReadRepository,
): LinkedLegs {
    val collected = mutableListOf(rootId)
    val visited = mutableSetOf(rootId)
    var passThroughLegCount = 0
    var frontier = listOf(rootId)
    while (frontier.isNotEmpty()) {
        val next = mutableListOf<TransferId>()
        for (id in frontier) {
            relationshipRepository
                .getByTransfer(id)
                .first()
                .asSequence()
                .filter { it.id1 == id }
                .filterNot { it.relationshipType.name == WellKnownIds.REVERSAL_RELATIONSHIP_TYPE_NAME }
                .filter { visited.add(it.id2) }
                .forEach { rel ->
                    if (rel.relationshipType.id.id == WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID) {
                        passThroughLegCount++
                    }
                    collected += rel.id2
                    next += rel.id2
                }
        }
        frontier = next
    }
    return LinkedLegs(collected, passThroughLegCount)
}

/**
 * Executes a re-import of [csvImport] under [strategy] following a confirmed [plan]:
 * 1. updates each planned value-changed row's transfer in place (amount/currency/date/description) —
 *    BEFORE the merges, while the persisted account ids the updates re-send still exist (a merge
 *    deletes the duplicate account and moves its transfers, updated ones included);
 * 2. merges each planned duplicate into its target (reversible via the account-merge history) —
 *    merging BEFORE re-running rows is required, otherwise dedupe would look for existing transfers
 *    on the newly-mapped accounts, miss the ones still sitting on the duplicates, and import them twice;
 * 3. rewrites each planned pass-through row: deletes its old transfer(s) and resets the row's status —
 *    deleting BEFORE re-running rows for the same reason (dedupe must not match the stale transfer);
 * 4. re-runs the strategy over rows never imported, errored, or just reset, which now resolve via the
 *    new mappings and route through the current pass-through chains;
 * 5. deletes import-created accounts left with no transfers (e.g. the raw "CRV*…" merchants);
 * 6. refreshes materialized views.
 *
 * [onProgress] reports each step (with counts where known) for a progress UI. [valueUpdateChunkSize]
 * is exposed for tests to force multiple update chunks with a small fixture.
 */
@Suppress("LongParameterList")
suspend fun executeCsvReimport(
    plan: ReimportPlan,
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    valueUpdateChunkSize: Int = REIMPORT_VALUE_UPDATE_CHUNK,
): CsvReimportResult {
    val merged = mutableListOf<ReimportMerge>()
    val skipped = plan.skipped.toMutableList()

    val updatedRows = applyValueUpdates(plan.valueUpdates, csvImport, importEngine, skipped, onProgress, valueUpdateChunkSize)

    // One batch per merge so a surprise failure (races past the read-only pre-checks) downgrades to a
    // per-account skip instead of aborting the remaining merges.
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
            logger.warn(expected) { "Re-import merge of '${merge.duplicateName}' into '${merge.targetName}' failed" }
            skipped +=
                ReimportSkippedAccount(
                    accountId = merge.duplicateId,
                    accountName = merge.duplicateName,
                    reason = ReimportSkipReason.MERGE_FAILED,
                    detail = expected.message ?: "Merge failed",
                )
        }
    }

    // One batch per rewrite so a failure downgrades to a per-row skip; the deletes and the row-status
    // reset ride in the same batch, so a row never loses its transfers without being queued for re-run.
    val rewritten = mutableListOf<ReimportRewrite>()
    for ((index, rewrite) in plan.rewrites.withIndex()) {
        onProgress?.invoke(
            ImportProgress(
                "Rewriting pass-through rows",
                fraction = index.toFloat() / plan.rewrites.size,
                processed = index,
                total = plan.rewrites.size,
            ),
        )
        try {
            importEngine.import(
                ImportBatch(
                    transfers =
                        rewrite.transferIdsToDelete.map { id ->
                            ImportTransfer(
                                source = Source.Csv(csvImport.id),
                                operation = ImportOperation.DELETE,
                                existingId = id,
                            )
                        },
                    dedupePolicy = DedupePolicy.None,
                    csvImportMutations = listOf(CsvImportMutation.ResetRowStatuses(csvImport.id, listOf(rewrite.rowIndex))),
                ),
            )
            rewritten += rewrite
        } catch (expected: Exception) {
            logger.warn(expected) { "Re-import rewrite of row ${rewrite.rowIndex} ('${rewrite.description}') failed" }
            skipped +=
                ReimportSkippedAccount(
                    accountId = null,
                    accountName = rewrite.description,
                    reason = ReimportSkipReason.REWRITE_FAILED,
                    detail = expected.message ?: "Rewrite failed",
                )
        }
    }

    val importResult =
        applyStagedCsv(
            csvImport = csvImport,
            strategy = strategy,
            sourceAccountOverride = sourceAccountOverride,
            currencies = currencies,
            accountMappingRepository = accountMappingRepository,
            accountRepository = accountRepository,
            csvImportRepository = csvImportRepository,
            maintenance = maintenance,
            importEngine = importEngine,
            refreshViews = false,
            passThroughAccounts = passThroughAccounts,
            onProgress = onProgress,
            engineBatchSize = REIMPORT_ENGINE_BATCH_SIZE,
        )

    onProgress?.invoke(ImportProgress("Cleaning up empty accounts"))
    val deletedEmptyAccounts = deleteEmptyImportCreatedAccounts(csvImport, accountRepository, csvImportRepository, importEngine)

    onProgress?.invoke(ImportProgress("Refreshing views"))
    maintenance.refreshMaterializedViews()

    return CsvReimportResult(
        mergedAccounts = merged,
        skipped = skipped,
        deletedEmptyAccounts = deletedEmptyAccounts,
        importResult = importResult,
        rewrittenRows = rewritten,
        updatedRows = updatedRows,
    )
}

/**
 * Applies the planned in-place transfer updates in chunks of [chunkSize], each an engine batch of
 * UPDATE intents plus the matching row-status writeback to UPDATED. Chunking exists for progress
 * reporting and costs nothing in atomicity: the engine's UPDATE phase applies intents one repository
 * call at a time (it never wraps them in a single transaction), so even a single big batch was never
 * all-or-nothing. A failing chunk falls back to per-row updates so one bad row downgrades to a skip
 * instead of blocking the rest. Returns the updates that were applied; failures are appended to
 * [skipped].
 */
private suspend fun applyValueUpdates(
    valueUpdates: List<ReimportValueUpdate>,
    csvImport: CsvImport,
    importEngine: ImportEngine,
    skipped: MutableList<ReimportSkippedAccount>,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    chunkSize: Int = REIMPORT_VALUE_UPDATE_CHUNK,
): List<ReimportValueUpdate> {
    if (valueUpdates.isEmpty()) return emptyList()

    fun batchFor(updates: List<ReimportValueUpdate>) =
        ImportBatch(
            transfers =
                updates.map { update ->
                    ImportTransfer(
                        source = Source.Csv(csvImport.id, update.rowIndex),
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
            csvImportMutations =
                listOf(
                    CsvImportMutation.UpdateRowStatuses(
                        id = csvImport.id,
                        status = ImportStatus.UPDATED.name,
                        rowTransferMap = updates.associate { it.rowIndex to it.transferId },
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
            logger.warn(expected) { "Re-import value update chunk failed, falling back to per-row updates" }
            for (update in chunk) {
                try {
                    importEngine.import(batchFor(listOf(update)))
                    updated += update
                } catch (expectedRowError: Exception) {
                    logger.warn(expectedRowError) {
                        "Re-import value update of row ${update.rowIndex} ('${update.description}') failed"
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
 * Deletes accounts this import created that hold no transfers (merged-away duplicates are already
 * gone — this catches ones emptied without being merge targets). Returns the deleted names.
 */
private suspend fun deleteEmptyImportCreatedAccounts(
    csvImport: CsvImport,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
): List<String> {
    val importCreated = csvImportRepository.getAccountsCreatedByImport(csvImport.id)
    val remainingById = accountRepository.getAllAccounts().first().associateBy { it.id }
    val emptyAccounts =
        importCreated
            .mapNotNull { remainingById[it] }
            .filter { accountRepository.countTransfersByAccount(it.id) == 0L }
    if (emptyAccounts.isEmpty()) return emptyList()

    importEngine.import(
        ImportBatch.manualEdits(
            accounts =
                emptyAccounts.map { account ->
                    ImportAccountIntent(
                        key = LocalAccountKey("reimport-delete-${account.id.id}"),
                        source = Source.Csv(csvImport.id),
                        operation = ImportOperation.DELETE,
                        existingId = account.id,
                    )
                },
        ),
    )
    return emptyAccounts.map { it.name }
}
