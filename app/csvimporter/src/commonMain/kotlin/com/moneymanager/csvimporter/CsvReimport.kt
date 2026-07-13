@file:OptIn(ExperimentalTime::class)

package com.moneymanager.csvimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.MergeId
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
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
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

    /** Reversing a merge whose accounts no longer consolidate failed; the merge was left as-is. */
    REVERSAL_FAILED,

    /** Deleting a row's old transfer for a transfer→trade conversion failed; the row was left as-is. */
    TRADE_CONVERSION_FAILED,
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
 * One already-imported row that the current strategy maps to a cross-asset TRADE but whose persisted
 * transaction is a single-asset transfer (imported before the strategy gained TO_CURRENCY/TO_AMOUNT
 * mappings, so the credited leg was lost and the counterparty is typically a junk description-named
 * account): the old transfer(s) are deleted, the row's status is reset, and the strategy re-run
 * imports it as a trade.
 */
data class ReimportTradeConversion(
    val rowIndex: Long,
    /** The row's existing transfer plus its linked legs, all deleted before the re-run. */
    val transferIdsToDelete: List<TransferId>,
    /** The row's statement description, for the preview. */
    val description: String,
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

/**
 * A previously-performed account merge the re-import will REVERSE because the current account mappings
 * no longer consolidate the merged-away account onto its survivor (e.g. the mapping was narrowed or
 * removed): the merge is undone, recreating the deleted account and moving its transfers back.
 */
data class ReimportReversal(
    val mergeId: MergeId,
    /** The account that will be recreated (was merged away). */
    val deletedAccountName: String,
    /** The survivor the transfers currently sit on. */
    val survivingName: String,
    val transferCount: Long,
    /** This import's row indexes whose transfers move back — excluded from in-place value updates. */
    val rowIndexes: Set<Long>,
)

/** The read-only preview of what a re-import would do, shown for confirmation before executing. */
data class ReimportPlan(
    val merges: List<ReimportMerge>,
    val skipped: List<ReimportSkippedAccount>,
    /** Already-imported rows to reroute through a pass-through conduit chain. */
    val rewrites: List<ReimportRewrite> = emptyList(),
    /** Already-imported rows whose transfer values (amount/currency/date/description) changed. */
    val valueUpdates: List<ReimportValueUpdate> = emptyList(),
    /** Prior merges to undo because the current mappings no longer consolidate them. */
    val reversals: List<ReimportReversal> = emptyList(),
    /** Already-imported single-asset transfers the current strategy now maps to cross-asset trades. */
    val tradeConversions: List<ReimportTradeConversion> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            merges.isEmpty() &&
                skipped.isEmpty() &&
                rewrites.isEmpty() &&
                valueUpdates.isEmpty() &&
                reversals.isEmpty() &&
                tradeConversions.isEmpty()
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
    /** Prior merges undone because the current mappings no longer consolidate them. */
    val reversedMerges: List<ReimportReversal> = emptyList(),
    /** Rows whose single-asset transfer was deleted and re-imported as a cross-asset trade. */
    val convertedRows: List<ReimportTradeConversion> = emptyList(),
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
 * Detects prior account merges this re-import should REVERSE: for each still-active merge whose
 * merged-away account was created by this import, it re-maps that account's moved transfers (traced back
 * to their source rows) under the CURRENT mappings. A merge is reversed only when its rows resolve to a NEW
 * account (no explicit mapping routes them anywhere) — the deleted account is recreated and its transfers
 * move back. If the rows still resolve to ANY existing account — the survivor (merge still valid) or a
 * different account they were remapped to — the merge is left as-is rather than sending the transfers to
 * the deleted account (which the value-update pass, skipping reversed rows, would never then correct). This
 * is the inverse of [computeReimportMerges] — it lets narrowing/removing a mapping split previously-
 * consolidated accounts back out. [rowIndexForSource] returns a row index only for source records belonging
 * to this import (CSV or QIF), so cross-import transfers on a shared account are ignored.
 */
@Suppress("LongParameterList")
suspend fun computeReimportReversals(
    importCreated: Set<AccountId>,
    mappedNoHistoryPrep: ImportPreparation,
    accountsById: Map<AccountId, Account>,
    accountRepository: AccountReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    rowIndexForSource: (Source) -> Long?,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
): List<ReimportReversal> {
    val candidates = accountRepository.getReversibleMerges().first().filter { it.deletedAccountId in importCreated }
    if (candidates.isEmpty()) return emptyList()

    onProgress?.invoke(ImportProgress("Checking merges to reverse"))
    val mappedByRow = mappedNoHistoryPrep.validTransfers.associateBy { it.rowIndex }
    val reversals = mutableListOf<ReimportReversal>()
    for (merge in candidates) {
        var sawRow = false
        var resolvesToExistingAccount = false
        val rowIndexes = mutableSetOf<Long>()
        for (moved in accountRepository.getMergeMovedTransfers(merge.id)) {
            val rowIndex =
                transferSourceRepository
                    .getSourcesForTransaction(moved.transferId)
                    .firstNotNullOfOrNull { rowIndexForSource(it.source) }
            val mapped = rowIndex?.let { mappedByRow[it] }
            if (rowIndex != null && mapped != null) {
                sawRow = true
                rowIndexes += rowIndex
                // The side that had pointed at the deleted account. Only reverse when it now resolves to a
                // NEW account (id <= 0) — i.e. no explicit mapping routes it anywhere, so splitting it back
                // to the merged-away account is exactly right. If it still resolves to ANY existing account
                // — the survivor (merge still valid) OR a different account it was remapped to — don't
                // reverse: reversing would wrongly send the transfer to the deleted account instead of that
                // target, and the value-update pass skips reversed rows so it would never be corrected.
                val resolved = if (moved.movedTarget) mapped.transfer.targetAccountId else mapped.transfer.sourceAccountId
                if (resolved.id > 0) resolvesToExistingAccount = true
            }
        }
        if (sawRow && !resolvesToExistingAccount) {
            reversals +=
                ReimportReversal(
                    mergeId = merge.id,
                    deletedAccountName = merge.deletedAccountName,
                    survivingName = accountsById[merge.survivingAccountId]?.name ?: "#${merge.survivingAccountId.id}",
                    transferCount = merge.transferCount,
                    rowIndexes = rowIndexes,
                )
        }
    }
    return reversals
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
    transferSourceRepository: TransferSourceReadRepository,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    cryptoAssets: List<CryptoAsset> = emptyList(),
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
            cryptoAssets,
        ).prepareImport(allRows)

    onProgress?.invoke(ImportProgress("Analyzing rows (pass 1 of 2)"))
    val mappedPrep = prep(mappings)
    onProgress?.invoke(ImportProgress("Analyzing rows (pass 2 of 2)"))
    val candidates = computeReimportMerges(prep(emptyList()), mappedPrep, importCreated)
    val accountsById = accounts.associateBy { it.id }
    // Reversal detection re-maps with the mappings but NO historical names, so a merged-away account's
    // name (now an audit alias of its survivor) doesn't mask a mapping that no longer routes it there.
    val mappedNoHistoryPrep =
        buildCsvMapper(
            strategy,
            csvImport.columns,
            accounts,
            currencies,
            mappings,
            effectiveSource,
            passThroughAccounts,
            cryptoAssets = cryptoAssets,
        ).prepareImport(allRows)
    val reversals =
        computeReimportReversals(
            importCreated = importCreated,
            mappedNoHistoryPrep = mappedNoHistoryPrep,
            accountsById = accountsById,
            accountRepository = accountRepository,
            transferSourceRepository = transferSourceRepository,
            rowIndexForSource = { source -> (source as? Source.Csv)?.takeIf { it.importId == csvImport.id }?.rowIndex },
            onProgress = onProgress,
        )
    // One batched load instead of a per-row getTransactionById round trip in each of the three
    // row scans below — on large files those thousands of single-row flow queries dominate the plan phase.
    onProgress?.invoke(ImportProgress("Loading existing transactions"))
    val existingTransfers = transactionRepository.getTransactionsByIds(allRows.mapNotNull { it.transferId })
    val rewrites =
        computeReimportRewrites(allRows, mappedPrep, relationshipRepository, onProgress) { transferId ->
            existingTransfers[transferId]
        }
    val tradeConversions =
        computeReimportTradeConversions(
            allRows = allRows,
            mappedPrep = mappedPrep,
            rewrittenRowIndexes = rewrites.map { it.rowIndex }.toSet(),
            relationshipRepository = relationshipRepository,
            onProgress = onProgress,
        ) { transferId -> existingTransfers[transferId] }
    // Rows whose transfers a reversal moves back are excluded from value updates: the reversal owns that
    // transfer's account move, and a value update would re-send the pre-reversal (survivor) accounts.
    // Trade-conversion rows are excluded too — their transfer is deleted, not updated.
    val reversalRowIndexes = reversals.flatMapTo(mutableSetOf()) { it.rowIndexes }
    val valueUpdates =
        computeReimportValueUpdates(
            allRows = allRows,
            mappedPrep = mappedPrep,
            rewrittenRowIndexes =
                rewrites.map { it.rowIndex }.toSet() +
                    reversalRowIndexes +
                    tradeConversions.map { it.rowIndex },
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
    return ReimportPlan(
        merges = merges,
        skipped = skipped,
        rewrites = rewrites,
        valueUpdates = valueUpdates,
        reversals = reversals,
        tradeConversions = tradeConversions,
    )
}

/**
 * Finds already-imported rows whose recomputed transfer values differ from the persisted transfer —
 * the strategy's amount/currency/date/description mappings changed since the row was imported. Only
 * value fields are compared; account differences are the domain of merges (and a persisted transfer
 * whose account was mapped away is matched pre-merge, so comparing accounts here would misfire).
 * Pass-through rows are excluded — value changes there invalidate every leg of the chain, so they
 * are handled by the rewrite path instead (see [computeReimportRewrites]).
 * [existingTransferLookup] loads a row's persisted transfer for the diff; a null result skips the row.
 */
suspend fun computeReimportValueUpdates(
    allRows: List<CsvRow>,
    mappedPrep: ImportPreparation,
    rewrittenRowIndexes: Set<Long>,
    existingTransferLookup: suspend (TransferId) -> Transfer?,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
): List<ReimportValueUpdate> {
    val rowsByIndex = allRows.associateBy { it.rowIndex }
    val updates = mutableListOf<ReimportValueUpdate>()
    val total = mappedPrep.validTransfers.size
    mappedPrep.validTransfers.forEachIndexed { index, mapped ->
        emitRowProgress(onProgress, "Checking rows for value changes", index, total)
        valueUpdateFor(mapped, rowsByIndex, rewrittenRowIndexes, existingTransferLookup)?.let { updates += it }
    }
    return updates
}

private suspend fun valueUpdateFor(
    mapped: CsvTransferWithAttributes,
    rowsByIndex: Map<Long, CsvRow>,
    rewrittenRowIndexes: Set<Long>,
    existingTransferLookup: suspend (TransferId) -> Transfer?,
): ReimportValueUpdate? {
    if (mapped.passThrough != null || mapped.rowIndex in rewrittenRowIndexes) return null
    val row = rowsByIndex[mapped.rowIndex] ?: return null
    if (row.importStatus != ImportStatus.IMPORTED && row.importStatus != ImportStatus.UPDATED) return null
    val transferId = row.transferId ?: return null
    val existing = existingTransferLookup(transferId) ?: return null
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

private fun Money.display(): String = "${toDisplayValue()} ${asset.code}"

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
    onProgress?.invoke(ImportProgress("Loading transfer links"))
    val linkedLegsByRoot =
        collectLinkedLegs(
            mappedPrep.validTransfers.mapNotNull { mapped ->
                if (mapped.passThrough == null) return@mapNotNull null
                rowsByIndex[mapped.rowIndex]
                    ?.takeIf { it.importStatus == ImportStatus.IMPORTED }
                    ?.transferId
            },
            relationshipRepository,
        )
    val rewrites = mutableListOf<ReimportRewrite>()
    val total = mappedPrep.validTransfers.size
    mappedPrep.validTransfers.forEachIndexed { index, mapped ->
        emitRowProgress(onProgress, "Checking pass-through rows", index, total)
        rewriteFor(mapped, rowsByIndex, linkedLegsByRoot, existingTransferLookup)?.let { rewrites += it }
    }
    return rewrites
}

private suspend fun rewriteFor(
    mapped: CsvTransferWithAttributes,
    rowsByIndex: Map<Long, CsvRow>,
    linkedLegsByRoot: Map<TransferId, LinkedLegs>,
    existingTransferLookup: suspend (TransferId) -> Transfer?,
): ReimportRewrite? {
    val passThrough = mapped.passThrough ?: return null
    val row = rowsByIndex[mapped.rowIndex] ?: return null
    if (row.importStatus != ImportStatus.IMPORTED) return null
    val transferId = row.transferId ?: return null
    val linked = linkedLegsByRoot.getValue(transferId)
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

/**
 * Finds already-imported rows the current strategy maps to a cross-asset TRADE (`tradeTo` set) whose
 * persisted transaction is still a single-asset transfer: their old transfer(s) are deleted and the
 * row re-runs as a trade. Only IMPORTED/UPDATED rows with a transfer id are considered — a DUPLICATE
 * row's transfer belongs to another row, and an already-converted row has a null transfer id (trades
 * don't write one back), which keeps this pass idempotent. Rows already planned as pass-through
 * rewrites are skipped: the rewrite deletes the old legs itself and the re-run imports the row fresh.
 * [existingTransferLookup] must return null when the id no longer resolves to a transfer.
 */
internal suspend fun computeReimportTradeConversions(
    allRows: List<CsvRow>,
    mappedPrep: ImportPreparation,
    rewrittenRowIndexes: Set<Long>,
    relationshipRepository: TransferRelationshipReadRepository,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    existingTransferLookup: suspend (TransferId) -> Transfer?,
): List<ReimportTradeConversion> {
    val rowsByIndex = allRows.associateBy { it.rowIndex }

    fun tradeConversionRoot(mapped: CsvTransferWithAttributes): TransferId? {
        if (mapped.tradeTo == null || mapped.rowIndex in rewrittenRowIndexes) return null
        val row = rowsByIndex[mapped.rowIndex] ?: return null
        if (row.importStatus != ImportStatus.IMPORTED && row.importStatus != ImportStatus.UPDATED) return null
        return row.transferId
    }
    val eligibleRoots =
        mappedPrep.validTransfers
            .mapNotNull(::tradeConversionRoot)
            .filterTo(mutableSetOf()) { existingTransferLookup(it) != null }
    val linkedLegsByRoot = collectLinkedLegs(eligibleRoots, relationshipRepository)
    val conversions = mutableListOf<ReimportTradeConversion>()
    val total = mappedPrep.validTransfers.size
    mappedPrep.validTransfers.forEachIndexed { index, mapped ->
        emitRowProgress(onProgress, "Checking rows converting to trades", index, total)
        val transferId = tradeConversionRoot(mapped) ?: return@forEachIndexed
        if (transferId !in eligibleRoots) return@forEachIndexed
        conversions +=
            ReimportTradeConversion(
                rowIndex = mapped.rowIndex,
                transferIdsToDelete = linkedLegsByRoot.getValue(transferId).transferIds,
                description = mapped.transfer.description,
            )
    }
    return conversions
}

/** A row's transfer plus everything hanging off it via forward relationships. */
private class LinkedLegs(
    val transferIds: List<TransferId>,
    /** Number of `pass-through` links followed — the length of the persisted conduit chain. */
    val passThroughLegCount: Int,
)

/**
 * For each root in [rootIds], collects the root plus every transfer reachable through FORWARD
 * relationships (this transfer as id1): fee legs and pass-through spend legs, transitively.
 * Reversal links are not followed — their id2 is another row's leg and must never be deleted with
 * that row. All roots are walked together, one batched relationship query per BFS depth, instead
 * of one query per transfer — on large files those thousands of single-id round trips used to
 * dominate the pass-through and trade-conversion scans.
 */
private suspend fun collectLinkedLegs(
    rootIds: Collection<TransferId>,
    relationshipRepository: TransferRelationshipReadRepository,
): Map<TransferId, LinkedLegs> {
    class MutableLegs(
        rootId: TransferId,
    ) {
        val collected = mutableListOf(rootId)
        val visited = mutableSetOf(rootId)
        var passThroughLegCount = 0
    }

    val legsByRoot = LinkedHashMap<TransferId, MutableLegs>()
    for (root in rootIds) legsByRoot.getOrPut(root) { MutableLegs(root) }
    // Each frontier entry pairs the root whose chain is being walked with the transfer to expand.
    var frontier: List<Pair<TransferId, TransferId>> = legsByRoot.keys.map { it to it }
    while (frontier.isNotEmpty()) {
        val forwardById =
            relationshipRepository
                .getByTransfers(frontier.map { it.second })
                .filterNot { it.relationshipType.name == WellKnownIds.REVERSAL_RELATIONSHIP_TYPE_NAME }
                .groupBy { it.id1 }
        val next = mutableListOf<Pair<TransferId, TransferId>>()
        for ((root, id) in frontier) {
            val legs = legsByRoot.getValue(root)
            forwardById[id]
                .orEmpty()
                .filter { legs.visited.add(it.id2) }
                .forEach { rel ->
                    if (rel.relationshipType.id.id == WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID) {
                        legs.passThroughLegCount++
                    }
                    legs.collected += rel.id2
                    next += root to rel.id2
                }
        }
        frontier = next
    }
    return legsByRoot.mapValues { (_, legs) -> LinkedLegs(legs.collected, legs.passThroughLegCount) }
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
 * 4. converts each planned trade row the same way: deletes its stale single-asset transfer(s) and
 *    resets the row so the re-run imports it as a cross-asset trade;
 * 5. re-runs the strategy over rows never imported, errored, or just reset, which now resolve via the
 *    new mappings and route through the current pass-through chains;
 * 6. deletes import-created accounts left with no transfers (e.g. the raw "CRV*…" merchants or the
 *    description-named counterparties emptied by trade conversions);
 * 7. refreshes materialized views.
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
    refreshViews: Boolean = true,
    cryptoRepository: CryptoReadRepository? = null,
    tradeRepository: TradeReadRepository? = null,
): CsvReimportResult {
    val merged = mutableListOf<ReimportMerge>()
    val skipped = plan.skipped.toMutableList()

    // Reverse no-longer-valid merges FIRST: recreate the merged-away accounts and move their transfers
    // back before value updates / merges / re-run touch them, so downstream steps see the split state.
    val reversedMerges = applyReimportReversals(plan.reversals, importEngine, skipped, onProgress)

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

    // One batch per conversion, mirroring the rewrite loop: the row's stale single-asset transfer(s)
    // are deleted and its status reset in the same batch, so the re-run below re-imports it as a trade.
    val converted = mutableListOf<ReimportTradeConversion>()
    for ((index, conversion) in plan.tradeConversions.withIndex()) {
        onProgress?.invoke(
            ImportProgress(
                "Converting transfers to trades",
                fraction = index.toFloat() / plan.tradeConversions.size,
                processed = index,
                total = plan.tradeConversions.size,
            ),
        )
        try {
            importEngine.import(
                ImportBatch(
                    transfers =
                        conversion.transferIdsToDelete.map { id ->
                            ImportTransfer(
                                source = Source.Csv(csvImport.id),
                                operation = ImportOperation.DELETE,
                                existingId = id,
                            )
                        },
                    dedupePolicy = DedupePolicy.None,
                    csvImportMutations = listOf(CsvImportMutation.ResetRowStatuses(csvImport.id, listOf(conversion.rowIndex))),
                ),
            )
            converted += conversion
        } catch (expected: Exception) {
            logger.warn(expected) { "Re-import trade conversion of row ${conversion.rowIndex} ('${conversion.description}') failed" }
            skipped +=
                ReimportSkippedAccount(
                    accountId = null,
                    accountName = conversion.description,
                    reason = ReimportSkipReason.TRADE_CONVERSION_FAILED,
                    detail = expected.message ?: "Trade conversion failed",
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
            cryptoRepository = cryptoRepository,
            onProgress = onProgress,
            engineBatchSize = REIMPORT_ENGINE_BATCH_SIZE,
        )

    onProgress?.invoke(ImportProgress("Cleaning up empty accounts"))
    val deletedEmptyAccounts =
        deleteEmptyImportCreatedAccounts(csvImport, accountRepository, csvImportRepository, importEngine, tradeRepository)

    if (refreshViews) {
        onProgress?.invoke(ImportProgress("Refreshing views"))
        maintenance.refreshMaterializedViews()
    }

    return CsvReimportResult(
        mergedAccounts = merged,
        skipped = skipped,
        deletedEmptyAccounts = deletedEmptyAccounts,
        importResult = importResult,
        rewrittenRows = rewritten,
        updatedRows = updatedRows,
        reversedMerges = reversedMerges,
        convertedRows = converted,
    )
}

/**
 * Undoes each planned merge reversal via an [ImportBatch.manualEdits] unmerge intent (recreating the
 * deleted account and moving its transfers back), one batch per reversal so a failure downgrades to a
 * per-item skip. Returns the reversals actually applied; failures are appended to [skipped].
 */
suspend fun applyReimportReversals(
    reversals: List<ReimportReversal>,
    importEngine: ImportEngine,
    skipped: MutableList<ReimportSkippedAccount>,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
): List<ReimportReversal> {
    if (reversals.isEmpty()) return emptyList()
    val reversed = mutableListOf<ReimportReversal>()
    for ((index, reversal) in reversals.withIndex()) {
        onProgress?.invoke(
            ImportProgress(
                "Reversing merges",
                fraction = index.toFloat() / reversals.size,
                processed = index,
                total = reversals.size,
            ),
        )
        try {
            importEngine.import(ImportBatch.manualEdits(accountUnmerges = listOf(reversal.mergeId)))
            reversed += reversal
        } catch (expected: Exception) {
            logger.warn(expected) { "Re-import reversal of merge '${reversal.deletedAccountName}' failed" }
            skipped +=
                ReimportSkippedAccount(
                    accountId = null,
                    accountName = reversal.deletedAccountName,
                    reason = ReimportSkipReason.REVERSAL_FAILED,
                    detail = expected.message ?: "Reversal failed",
                )
        }
    }
    return reversed
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
 * Deletes accounts this import created that hold no transactions (merged-away duplicates are already
 * gone — this catches ones emptied without being merge targets). Returns the deleted names. Trades
 * count as activity: deleting an account cascades to its trades, so an account whose only movements
 * are trades (e.g. the wallet a transfer→trade conversion just re-imported into) must survive.
 */
private suspend fun deleteEmptyImportCreatedAccounts(
    csvImport: CsvImport,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
    tradeRepository: TradeReadRepository?,
): List<String> {
    val importCreated = csvImportRepository.getAccountsCreatedByImport(csvImport.id)
    val remainingById = accountRepository.getAllAccounts().first().associateBy { it.id }
    val candidates = importCreated.mapNotNull { remainingById[it] }
    // Two batched membership checks instead of two COUNT queries per candidate account.
    val withTransfers = accountRepository.accountsWithTransfers(candidates.map { it.id })
    val withTrades = tradeRepository?.accountsWithTrades(candidates.map { it.id }).orEmpty()
    val emptyAccounts = candidates.filter { it.id !in withTransfers && it.id !in withTrades }
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

/**
 * Summary of a bulk re-import run across many already-imported files. [filesImported] counts files a
 * re-import actually ran over (had a resolvable strategy). [merges] and [skipped] carry the actual
 * duplicate-account consolidations performed (and the ones that could not be merged) across all files,
 * so the UI can show WHICH accounts merged — the per-file preview's key detail the bulk path drops.
 */
data class CsvBulkReimportResult(
    override val filesImported: Int,
    override val transfersCreated: Int,
    override val duplicatesSkipped: Int,
    override val filesSkippedNoStrategy: Int,
    override val filesFailed: Int,
    val merges: List<ReimportMerge>,
    val reversals: List<ReimportReversal>,
    val valueUpdates: Int,
    val tradeConversions: Int,
    val emptyAccountsDeleted: Int,
    val skipped: List<ReimportSkippedAccount>,
) : BulkImportResult {
    override fun toSummary(): String =
        buildString {
            append("Re-imported $filesImported file${if (filesImported == 1) "" else "s"}")
            append(" · $transfersCreated new")
            if (valueUpdates > 0) append(" · $valueUpdates updated")
            if (tradeConversions > 0) append(" · $tradeConversions converted to trades")
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
 * Re-imports every already-imported [imports] file in one go: for each, resolves the strategy it was
 * last imported with ([CsvImport.lastAppliedStrategyId], falling back to content-aware auto-selection),
 * then plans and executes a re-import so current strategy/mapping changes apply retroactively (duplicate
 * accounts merged, changed transfer values updated, never-imported/errored rows imported, emptied
 * import-created accounts removed). Files with no resolvable strategy are skipped and counted.
 * Refreshes materialized views once at the end. Mirrors [bulkApplyCsv]; reports row-weighted
 * run-wide progress via [onProgress].
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
suspend fun bulkReimportCsv(
    imports: List<CsvImport>,
    sourceAccountOverride: AccountId?,
    strategies: List<CsvImportStrategy>,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    transactionRepository: TransactionReadRepository,
    relationshipRepository: TransferRelationshipReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: suspend (BulkImportProgress) -> Unit,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    cryptoRepository: CryptoReadRepository? = null,
    tradeRepository: TradeReadRepository? = null,
): CsvBulkReimportResult {
    var filesImported = 0
    var transfers = 0
    var duplicates = 0
    var skippedNoStrategy = 0
    var failed = 0
    val merges = mutableListOf<ReimportMerge>()
    val reversals = mutableListOf<ReimportReversal>()
    var valueUpdates = 0
    var tradeConversions = 0
    var emptyAccountsDeleted = 0
    val skipped = mutableListOf<ReimportSkippedAccount>()
    // Create every crypto asset the batch needs up front, sized to the batch-wide max precision per
    // ticker, so cross-file ordering never leaves a ticker missing or a scale factor too small (the
    // net-new ERROR rows a re-import picks up are denominated in these assets).
    val tracker = BulkProgressTracker(imports.map { it.rowCount }, onProgress)
    tracker.started(detail = "Preparing")
    val cryptoAssets =
        ensureCryptoAssetsForImports(imports, strategies, currencies, csvImportRepository, importEngine, cryptoRepository)

    imports.forEachIndexed { index, listedImport ->
        tracker.fileStarted(index, listedImport.originalFileName)
        // getAllImports() doesn't populate columns; re-fetch the full import so the strategy match works.
        val csvImport = csvImportRepository.getImport(listedImport.id).first() ?: listedImport
        val sampleRows = csvImportRepository.getImportRows(csvImport.id, limit = STRATEGY_CONTENT_SAMPLE_SIZE, offset = 0)
        // Prefer the strategy the file was last imported with (matches the per-file re-import dialog);
        // fall back to content-aware auto-selection if it was deleted or was never recorded.
        val matched =
            csvImport.lastAppliedStrategyId?.let { id -> strategies.find { it.id == id } }
                ?: strategies.selectForCsv(csvImport.originalFileName, csvImport.columns, sampleRows)
        if (matched == null) {
            skippedNoStrategy++
            return@forEachIndexed
        }
        try {
            val plan =
                planCsvReimport(
                    csvImport = csvImport,
                    strategy = matched,
                    sourceAccountOverride = sourceAccountOverride,
                    currencies = currencies,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    csvImportRepository = csvImportRepository,
                    transactionRepository = transactionRepository,
                    relationshipRepository = relationshipRepository,
                    transferSourceRepository = transferSourceRepository,
                    passThroughAccounts = passThroughAccounts,
                    onProgress = { tracker.phase(index, csvImport.originalFileName, it) },
                    cryptoAssets = cryptoAssets,
                )
            val result =
                executeCsvReimport(
                    plan = plan,
                    csvImport = csvImport,
                    strategy = matched,
                    sourceAccountOverride = sourceAccountOverride,
                    currencies = currencies,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    csvImportRepository = csvImportRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    passThroughAccounts = passThroughAccounts,
                    onProgress = { tracker.phase(index, csvImport.originalFileName, it) },
                    refreshViews = false,
                    cryptoRepository = cryptoRepository,
                    tradeRepository = tradeRepository,
                )
            filesImported++
            transfers += result.importResult?.successCount ?: 0
            duplicates += result.importResult?.duplicateCount ?: 0
            merges += result.mergedAccounts
            reversals += result.reversedMerges
            valueUpdates += result.updatedRows.size
            tradeConversions += result.convertedRows.size
            emptyAccountsDeleted += result.deletedEmptyAccounts.size
            skipped += result.skipped
        } catch (expected: Exception) {
            logger.error(expected) { "Bulk CSV re-import failed for ${csvImport.originalFileName}: ${expected.message}" }
            failed++
        }
    }

    tracker.done()
    maintenance.refreshMaterializedViews()

    return CsvBulkReimportResult(
        filesImported = filesImported,
        transfersCreated = transfers,
        duplicatesSkipped = duplicates,
        filesSkippedNoStrategy = skippedNoStrategy,
        filesFailed = failed,
        merges = merges,
        reversals = reversals,
        valueUpdates = valueUpdates,
        tradeConversions = tradeConversions,
        emptyAccountsDeleted = emptyAccountsDeleted,
        skipped = skipped,
    )
}
