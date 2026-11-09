package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.importengineapi.ManualEdit
import com.moneymanager.importengineapi.ReappliedLinks
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
    /** Files nobody picked that the run re-imported because they depend on what the picked ones create. */
    val cascadedFiles: Int = 0,
    /** Such files whose transactions the run deleted but couldn't import again (no strategy, a failed or stopped run). */
    val cascadedFilesNotRerun: Int = 0,
    val reappliedEdits: Int = 0,
    /** Hand edits whose transaction the re-import didn't create again, so they were dropped. */
    val unmatchedEdits: List<ManualEdit> = emptyList(),
    val reappliedLinks: ReappliedLinks = ReappliedLinks(),
    override val filesNotStarted: Int = 0,
) : BulkImportResult {
    override fun toSummary(): String =
        buildString {
            append("Re-imported $filesImported file${if (filesImported == 1) "" else "s"}")
            append(" · $transfersCreated imported")
            if (reappliedEdits > 0) append(" · $reappliedEdits manual edit${if (reappliedEdits == 1) "" else "s"} re-applied")
            if (unmatchedEdits.isNotEmpty()) append(" · ${unmatchedEdits.size} manual edits not re-applied")
            append(reappliedLinks.summary(" · "))
            if (cascadedFiles > 0) append(" · $cascadedFiles other file${if (cascadedFiles == 1) "" else "s"} re-imported along")
            if (cascadedFilesNotRerun > 0) {
                append(" · $cascadedFilesNotRerun other file${if (cascadedFilesNotRerun == 1) "" else "s"} left for their own re-import")
            }
            if (emptyAccountsDeleted > 0) append(" · $emptyAccountsDeleted empty removed")
            if (duplicatesSkipped > 0) append(" · $duplicatesSkipped duplicates skipped")
            if (filesSkippedNoStrategy > 0) append(" · $filesSkippedNoStrategy skipped (no strategy)")
            if (filesFailed > 0) append(" · $filesFailed failed")
            append(stoppedSuffix())
        }
}

/** What one file's turn in a bulk re-import produced; see [runBulkReimport]. */
sealed interface BulkReimportFileOutcome {
    /** The file was re-imported. */
    data class Reimported(
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
 * a [WeightedProgress], and a per-file try/catch that logs and counts a failure instead of aborting the run.
 * The caller refreshes the materialized views once, after the run.
 *
 * [prepare] runs once after the bar appears and before the first file, producing the run-wide context
 * [reimportFile] needs. It prepares each file (weighted by its rows, at the start of the bar) and reports each,
 * by its index in [imports], as it's done. [logLabel] names the flow in the
 * per-file failure log ("CSV", "QIF").
 *
 * [stopRequested] is asked before each file: once it says yes, the run starts no more files (the one under way
 * finishes) and counts the rest in [BulkReimportResult.filesNotStarted].
 */
@Suppress("LongParameterList")
suspend fun <T, C> runBulkReimport(
    imports: List<T>,
    rowCount: (T) -> Int,
    fileName: (T) -> String,
    logLabel: String,
    onProgress: suspend (Progress) -> Unit,
    startDetail: String? = null,
    stopRequested: () -> Boolean = { false },
    prepare: suspend (onPrepared: suspend (index: Int) -> Unit) -> C,
    reimportFile: suspend (index: Int, file: T, context: C, onFileProgress: suspend (Progress) -> Unit) -> BulkReimportFileOutcome,
): BulkReimportResult {
    var result = BulkReimportResult(0, 0, 0, 0, 0)

    val tracker =
        WeightedProgress(
            weights = imports.map(rowCount),
            preparation = imports.map(rowCount),
            preparationNoun = "files",
            onProgress = onProgress,
        )
    tracker.started(detail = startDetail)
    val context = prepare { index -> tracker.prepared(index, "Prepared", fileName(imports[index])) }

    for ((index, file) in imports.withIndex()) {
        if (stopRequested()) {
            result = result.copy(filesNotStarted = imports.size - index)
            break
        }
        val name = fileName(file)
        tracker.itemStarted(index, name)
        try {
            when (val outcome = reimportFile(index, file, context) { tracker.item(index, name, it) }) {
                is BulkReimportFileOutcome.NoWork -> {}

                is BulkReimportFileOutcome.NoStrategy -> {
                    result = result.copy(filesSkippedNoStrategy = result.filesSkippedNoStrategy + 1)
                }

                is BulkReimportFileOutcome.Reimported -> {
                    result =
                        result.copy(
                            filesImported = result.filesImported + 1,
                            transfersCreated = result.transfersCreated + outcome.successCount,
                            duplicatesSkipped = result.duplicatesSkipped + outcome.duplicateCount,
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

    tracker.done(imports.size - result.filesNotStarted)
    return result
}
