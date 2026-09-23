package com.moneymanager.csvimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportTransfer
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * Summary of a bulk re-import run across many already-imported files, shared by the CSV and QIF flows.
 * [filesImported] counts files a re-import actually ran over (had a resolvable strategy). [merges] and
 * [skipped] carry the actual duplicate-account consolidations performed (and the ones that could not be
 * merged) across all files, so the UI can show WHICH accounts merged — the per-file preview's key detail
 * the bulk path drops.
 *
 * The counters after [valueUpdates] only ever move on the CSV path (QIF re-import has no trade
 * conversion, counterparty reconcile or stale-duplicate step), so they default to zero and their summary
 * fragments simply never render for QIF.
 */
data class BulkReimportResult(
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
    val tradeConversions: Int = 0,
    /** Rows re-run so their unidentified counterparty is rebooked and/or reconciled away. */
    val counterpartyReconciles: Int = 0,
    /** Trade rows whose duplicate conversion (another export's wording of it) was removed. */
    val duplicateTrades: Int = 0,
    /** Rows released for re-import because the transfer they claimed is stale or shared. */
    val staleDuplicates: Int = 0,
) : BulkImportResult {
    override fun toSummary(): String =
        buildString {
            append("Re-imported $filesImported file${if (filesImported == 1) "" else "s"}")
            append(" · $transfersCreated new")
            if (valueUpdates > 0) append(" · $valueUpdates updated")
            if (tradeConversions > 0) append(" · $tradeConversions converted to trades")
            if (counterpartyReconciles > 0) append(" · $counterpartyReconciles rebooked")
            if (duplicateTrades > 0) {
                append(" · $duplicateTrades duplicate conversion${if (duplicateTrades == 1) "" else "s"} removed")
            }
            if (staleDuplicates > 0) {
                append(" · $staleDuplicates row${if (staleDuplicates == 1) "" else "s"} released")
            }
            if (merges.isNotEmpty()) append(" · ${merges.size} account${if (merges.size == 1) "" else "s"} merged")
            if (reversals.isNotEmpty()) append(" · ${reversals.size} merge${if (reversals.size == 1) "" else "s"} reversed")
            if (emptyAccountsDeleted > 0) append(" · $emptyAccountsDeleted empty removed")
            if (duplicatesSkipped > 0) append(" · $duplicatesSkipped duplicates skipped")
            if (filesSkippedNoStrategy > 0) append(" · $filesSkippedNoStrategy skipped (no strategy)")
            if (skipped.isNotEmpty()) append(" · ${skipped.size} not merged/updated")
            if (filesFailed > 0) append(" · $filesFailed failed")
        }
}

/** What one file's turn in a bulk re-import produced; see [runBulkReimport]. */
sealed interface BulkReimportFileOutcome {
    /** The file was planned and executed; its [result] is folded into the run totals. */
    data class Reimported(
        val result: CsvReimportResult,
    ) : BulkReimportFileOutcome

    /** No strategy could be resolved for the file, so nothing ran — counted in `filesSkippedNoStrategy`. */
    data object NoStrategy : BulkReimportFileOutcome

    /** The file had nothing to re-import at all; it is not counted anywhere. */
    data object NoWork : BulkReimportFileOutcome
}

/**
 * Runs [reimportFile] over every file in [imports] and folds the per-file [CsvReimportResult]s into one
 * [BulkReimportResult] — the machinery both `bulkReimportCsv` and `bulkReimportQif` need: row-weighted
 * run-wide progress via a [BulkProgressTracker], a per-file try/catch that logs and counts a failure
 * instead of aborting the run, and a single closing [Maintenance.refreshMaterializedViews] (each file's
 * own execute is expected to be called with `refreshViews = false`).
 *
 * [prepare] runs once after the bar appears and before the first file, producing the run-wide context
 * [reimportFile] needs (crypto assets, historical source accounts, …); a flow with nothing to prepare
 * passes `Unit`. [logLabel] names the flow in the per-file failure log ("CSV", "QIF").
 */
@Suppress("LongParameterList")
suspend fun <T, C> runBulkReimport(
    imports: List<T>,
    rowCount: (T) -> Int,
    fileName: (T) -> String,
    logLabel: String,
    maintenance: Maintenance,
    onProgress: suspend (BulkImportProgress) -> Unit,
    startDetail: String? = null,
    prepare: suspend () -> C,
    reimportFile: suspend (index: Int, file: T, context: C, onFileProgress: suspend (ImportProgress) -> Unit) -> BulkReimportFileOutcome,
): BulkReimportResult {
    var filesImported = 0
    var transfers = 0
    var duplicates = 0
    var skippedNoStrategy = 0
    var failed = 0
    val merges = mutableListOf<ReimportMerge>()
    val reversals = mutableListOf<ReimportReversal>()
    var valueUpdates = 0
    var tradeConversions = 0
    var counterpartyReconciles = 0
    var duplicateTrades = 0
    var staleDuplicates = 0
    var emptyAccountsDeleted = 0
    val skipped = mutableListOf<ReimportSkippedAccount>()

    val tracker = BulkProgressTracker(imports.map(rowCount), onProgress)
    tracker.started(detail = startDetail)
    val context = prepare()

    imports.forEachIndexed { index, file ->
        val name = fileName(file)
        tracker.fileStarted(index, name)
        try {
            when (val outcome = reimportFile(index, file, context) { tracker.phase(index, name, it) }) {
                is BulkReimportFileOutcome.NoWork -> Unit
                is BulkReimportFileOutcome.NoStrategy -> skippedNoStrategy++
                is BulkReimportFileOutcome.Reimported -> {
                    val result = outcome.result
                    filesImported++
                    transfers += result.importResult?.successCount ?: 0
                    duplicates += result.importResult?.duplicateCount ?: 0
                    merges += result.mergedAccounts
                    reversals += result.reversedMerges
                    valueUpdates += result.updatedRows.size
                    tradeConversions += result.convertedRows.size
                    counterpartyReconciles += result.counterpartyReconciledRows.size
                    duplicateTrades += result.duplicateTradeRows.size
                    staleDuplicates += result.staleDuplicateRows.size
                    emptyAccountsDeleted += result.deletedEmptyAccounts.size
                    skipped += result.skipped
                }
            }
        } catch (expected: Exception) {
            logger.error(expected) { "Bulk $logLabel re-import failed for $name: ${expected.message}" }
            failed++
        }
    }

    tracker.done()
    maintenance.refreshMaterializedViews()

    return BulkReimportResult(
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
        tradeConversions = tradeConversions,
        counterpartyReconciles = counterpartyReconciles,
        duplicateTrades = duplicateTrades,
        staleDuplicates = staleDuplicates,
    )
}

/**
 * Applies the planned in-place transfer updates in chunks of [chunkSize], each an engine batch of
 * UPDATE intents plus the caller's matching row/record-status writeback to UPDATED ([batchFor] pairs the
 * transfers built here with the CSV or QIF status mutation). Chunking exists for progress reporting and
 * costs nothing in atomicity: the engine's UPDATE phase applies intents one repository call at a time
 * (it never wraps them in a single transaction), so even a single big batch was never all-or-nothing. A
 * failing chunk falls back to per-row updates so one bad row downgrades to a skip instead of blocking the
 * rest. Returns the updates that were applied; failures are appended to [skipped].
 *
 * [logLabel] and [rowLabel] only shape the log wording ("Re-import"/"QIF re-import", "row"/"record").
 */
@Suppress("LongParameterList")
suspend fun applyReimportValueUpdates(
    valueUpdates: List<ReimportValueUpdate>,
    importEngine: ImportEngine,
    skipped: MutableList<ReimportSkippedAccount>,
    sourceFor: (rowIndex: Long) -> Source,
    batchFor: (transfers: List<ImportTransfer>, rowTransferMap: Map<Long, TransferId>) -> ImportBatch,
    logLabel: String,
    rowLabel: String,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    chunkSize: Int = REIMPORT_VALUE_UPDATE_CHUNK,
): List<ReimportValueUpdate> {
    if (valueUpdates.isEmpty()) return emptyList()

    fun batchOf(updates: List<ReimportValueUpdate>) =
        batchFor(
            updates.map { update ->
                ImportTransfer(
                    source = sourceFor(update.rowIndex),
                    operation = ImportOperation.UPDATE,
                    existingId = update.transferId,
                    fromAccount = AccountRef.Existing(update.sourceAccountId),
                    toAccount = AccountRef.Existing(update.targetAccountId),
                    timestamp = update.newTimestamp,
                    description = update.newDescription,
                    amount = update.newAmount,
                )
            },
            updates.associate { it.rowIndex to it.transferId },
        )

    val total = valueUpdates.size
    val updated = mutableListOf<ReimportValueUpdate>()
    var done = 0
    onProgress?.invoke(ImportProgress("Updating transactions", fraction = 0f, processed = 0, total = total))
    for (chunk in valueUpdates.chunked(chunkSize.coerceAtLeast(1))) {
        try {
            importEngine.import(batchOf(chunk))
            updated += chunk
        } catch (expected: Exception) {
            logger.warn(expected) { "$logLabel value update chunk failed, falling back to per-$rowLabel updates" }
            for (update in chunk) {
                try {
                    importEngine.import(batchOf(listOf(update)))
                    updated += update
                } catch (expectedRowError: Exception) {
                    logger.warn(expectedRowError) {
                        "$logLabel value update of $rowLabel ${update.rowIndex} ('${update.description}') failed"
                    }
                    skipped +=
                        ReimportSkippedAccount(
                            accountId = null,
                            accountName = update.description,
                            detail = skipDetail("Update failed", expectedRowError.message),
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
