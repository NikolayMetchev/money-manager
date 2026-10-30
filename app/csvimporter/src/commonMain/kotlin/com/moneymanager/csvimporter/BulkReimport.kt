package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.Maintenance
import com.moneymanager.importengineapi.ManualEdit
import com.moneymanager.importengineapi.SourceRerunResult
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.WeightedProgress
import kotlinx.coroutines.CancellationException

private val logger = Logger.withTag("BulkReimport")

/**
 * Summary of a bulk re-import run across many already-imported files, shared by the CSV and QIF flows.
 * [filesImported] counts files a re-import actually ran over (had a resolvable strategy).
 */
data class BulkReimportResult(
    override val filesImported: Int,
    override val transfersCreated: Int,
    override val duplicatesSkipped: Int,
    override val filesSkippedNoStrategy: Int,
    override val filesFailed: Int,
    val emptyAccountsDeleted: Int = 0,
    /** Rows of other files matched against a deleted transaction, re-run with their own strategy. */
    val affectedRecords: Int = 0,
    /** Rows of other files reset but not re-run (see `SourceRerunResult.notRerun`); their file's re-import runs them. */
    val affectedRecordsNotRerun: Int = 0,
    val reappliedEdits: Int = 0,
    /** Hand edits whose transaction the re-import didn't create again, so they were dropped. */
    val unmatchedEdits: List<ManualEdit> = emptyList(),
) : BulkImportResult {
    override fun toSummary(): String =
        buildString {
            append("Re-imported $filesImported file${if (filesImported == 1) "" else "s"}")
            append(" · $transfersCreated imported")
            if (reappliedEdits > 0) append(" · $reappliedEdits manual edit${if (reappliedEdits == 1) "" else "s"} re-applied")
            if (unmatchedEdits.isNotEmpty()) append(" · ${unmatchedEdits.size} manual edits not re-applied")
            if (affectedRecords > 0) append(" · $affectedRecords rows of other files re-linked")
            if (affectedRecordsNotRerun > 0) {
                append(" · $affectedRecordsNotRerun rows of other files left for those files' own re-import")
            }
            if (emptyAccountsDeleted > 0) append(" · $emptyAccountsDeleted empty removed")
            if (duplicatesSkipped > 0) append(" · $duplicatesSkipped duplicates skipped")
            if (filesSkippedNoStrategy > 0) append(" · $filesSkippedNoStrategy skipped (no strategy)")
            if (filesFailed > 0) append(" · $filesFailed failed")
        }
}

/** What one file's turn in a bulk re-import produced; see [runBulkReimport]. */
sealed interface BulkReimportFileOutcome {
    /** The file was re-imported; its [result] is folded into the run totals. */
    data class Reimported(
        val result: SourceRerunResult<*>,
        val successCount: Int,
        val duplicateCount: Int,
    ) : BulkReimportFileOutcome

    /** No strategy could be resolved for the file, so nothing ran — counted in `filesSkippedNoStrategy`. */
    data object NoStrategy : BulkReimportFileOutcome

    /** The file had nothing to re-import at all; it is not counted anywhere. */
    data object NoWork : BulkReimportFileOutcome
}

/**
 * Runs [reimportFile] over every file in [imports] and folds the outcomes into one [BulkReimportResult]
 * — the machinery re-imports need: row-weighted run-wide progress via
 * a [WeightedProgress], a per-file try/catch that logs and counts a failure instead of aborting the run,
 * and a single closing [Maintenance.refreshMaterializedViews] (each file's own re-import is expected to
 * be called with `refreshViews = false`).
 *
 * [prepare] runs once after the bar appears and before the first file, producing the run-wide context
 * [reimportFile] needs; a flow with nothing to prepare passes `Unit`. [logLabel] names the flow in the
 * per-file failure log ("CSV", "QIF").
 */
@Suppress("LongParameterList")
suspend fun <T, C> runBulkReimport(
    imports: List<T>,
    rowCount: (T) -> Int,
    fileName: (T) -> String,
    logLabel: String,
    maintenance: Maintenance,
    onProgress: suspend (Progress) -> Unit,
    startDetail: String? = null,
    prepare: suspend () -> C,
    reimportFile: suspend (index: Int, file: T, context: C, onFileProgress: suspend (Progress) -> Unit) -> BulkReimportFileOutcome,
): BulkReimportResult {
    var result = BulkReimportResult(0, 0, 0, 0, 0)

    val tracker = WeightedProgress(imports.map(rowCount), onProgress = onProgress)
    tracker.started(detail = startDetail)
    val context = prepare()

    imports.forEachIndexed { index, file ->
        val name = fileName(file)
        tracker.itemStarted(index, name)
        try {
            when (val outcome = reimportFile(index, file, context) { tracker.item(index, name, it) }) {
                is BulkReimportFileOutcome.NoWork -> {}

                is BulkReimportFileOutcome.NoStrategy -> {
                    result = result.copy(filesSkippedNoStrategy = result.filesSkippedNoStrategy + 1)
                }

                is BulkReimportFileOutcome.Reimported -> {
                    val rerun = outcome.result
                    result =
                        result.copy(
                            filesImported = result.filesImported + 1,
                            transfersCreated = result.transfersCreated + outcome.successCount,
                            duplicatesSkipped = result.duplicatesSkipped + outcome.duplicateCount,
                            emptyAccountsDeleted = result.emptyAccountsDeleted + rerun.deletedEmptyAccounts.size,
                            affectedRecords = result.affectedRecords + rerun.affected.count,
                            affectedRecordsNotRerun = result.affectedRecordsNotRerun + rerun.notRerun.count,
                            reappliedEdits = result.reappliedEdits + rerun.reappliedEdits.size,
                            unmatchedEdits = result.unmatchedEdits + rerun.unmatchedEdits,
                        )
                }
            }
        } catch (expected: CancellationException) {
            throw expected
        } catch (expected: Exception) {
            logger.e(expected) { "Bulk $logLabel re-import failed for $name: ${expected.message}" }
            result = result.copy(filesFailed = result.filesFailed + 1)
        }
    }

    tracker.done()
    maintenance.refreshMaterializedViews()
    return result
}
