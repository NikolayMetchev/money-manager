package com.moneymanager.csvimporter

import com.moneymanager.importengineapi.ImportProgress
import kotlin.math.roundToLong

/** Engine write-batch size used by bulk imports so the progress bar advances per chunk, not per file. */
const val BULK_ENGINE_BATCH_SIZE = 250

/**
 * Overall progress of a bulk import run (CSV or QIF): a file counter plus a row-weighted overall
 * fraction, so one determinate bar covers the whole run and advances within large files.
 */
data class BulkImportProgress(
    val filesDone: Int,
    val filesTotal: Int,
    /** 0..1 across the whole run, monotonically non-decreasing. */
    val overallFraction: Float,
    /** Rows processed so far across the run (derived from [overallFraction], so equally monotonic). */
    val rowsDone: Long,
    /** Total rows across all files in the run, known upfront. */
    val rowsTotal: Long,
    val currentFileName: String? = null,
    val detail: String? = null,
)

/**
 * Aggregates per-file engine [ImportProgress] into one run-wide [BulkImportProgress]. Each file
 * contributes its row count's share of the bar; within a file the current phase's
 * [ImportProgress.fraction] scales that share. Re-import files emit several independent 0..1 fraction
 * sweeps (reversals, value updates, merges, transactions), so emissions are clamped monotonic — the bar
 * advances on the dominant sweep and holds otherwise, never regressing. [ImportProgress.processed]/
 * [ImportProgress.total] are deliberately ignored: engine totals are post-dedup transfers, not raw rows,
 * so only the fraction is comparable across files.
 */
class BulkProgressTracker(
    fileWeights: List<Int>,
    private val onProgress: suspend (BulkImportProgress) -> Unit,
) {
    private val filesTotal = fileWeights.size
    private val rowsBefore = fileWeights.runningFold(0L) { acc, weight -> acc + weight }
    private val weights = fileWeights
    private val rowsTotal = rowsBefore.last()
    private val totalRows = rowsTotal.coerceAtLeast(1)
    private var lastFraction = 0f

    /** Emits an initial 0-progress update so the bar appears before any per-file work starts. */
    suspend fun started(detail: String? = null) {
        onProgress(
            BulkImportProgress(
                filesDone = 0,
                filesTotal = filesTotal,
                overallFraction = lastFraction,
                rowsDone = rowsDone(),
                rowsTotal = rowsTotal,
                detail = detail,
            ),
        )
    }

    /** Marks file [index] as the one being processed (its own fraction starts at 0). */
    suspend fun fileStarted(
        index: Int,
        fileName: String,
    ) {
        emit(index, fileName, phaseFraction = 0f, detail = null)
    }

    /** Forwards an engine [progress] update for file [index], scaled into the file's share of the bar. */
    suspend fun phase(
        index: Int,
        fileName: String,
        progress: ImportProgress,
    ) {
        emit(index, fileName, phaseFraction = progress.fraction ?: 0f, detail = progress.detail)
    }

    /** Emits the terminal 100% update. */
    suspend fun done() {
        lastFraction = 1f
        onProgress(
            BulkImportProgress(
                filesDone = filesTotal,
                filesTotal = filesTotal,
                overallFraction = 1f,
                rowsDone = rowsTotal,
                rowsTotal = rowsTotal,
            ),
        )
    }

    private suspend fun emit(
        index: Int,
        fileName: String,
        phaseFraction: Float,
        detail: String?,
    ) {
        val fileShare = phaseFraction.coerceIn(0f, 1f) * weights[index]
        val overall = ((rowsBefore[index] + fileShare) / totalRows.toFloat()).coerceIn(0f, 1f)
        lastFraction = maxOf(lastFraction, overall)
        onProgress(
            BulkImportProgress(
                filesDone = index,
                filesTotal = filesTotal,
                overallFraction = lastFraction,
                rowsDone = rowsDone(),
                rowsTotal = rowsTotal,
                currentFileName = fileName,
                detail = detail,
            ),
        )
    }

    // Derived from the clamped fraction rather than tracked separately, so the row counter is exactly
    // as monotonic as the bar it sits next to.
    private fun rowsDone(): Long = (lastFraction * rowsTotal).roundToLong().coerceIn(0L, rowsTotal)
}
