package com.moneymanager.tasks

import kotlin.math.roundToLong

/**
 * Aggregates per-item [Progress] (one item per file of a bulk import) into one run-wide [Progress].
 * Each item contributes its weight's share of the bar (its row count); within an item the current
 * phase's [Progress.fraction] scales that share. Re-imports emit several independent 0..1 sweeps per
 * item (reversals, value updates, merges, transactions), so emissions are clamped monotonic: the bar
 * advances on the dominant sweep and holds otherwise, never regressing. The inner
 * [Progress.processed]/[Progress.total] are deliberately ignored: engine totals are post-dedup
 * transfers, not raw rows, so only the fraction is comparable across items.
 *
 * Each emission carries the overall fraction, the weighted units done/total (rows) as
 * [Progress.processed]/[Progress.total], and a detail naming the item: `File 2 of 5 — b.csv: phase`.
 */
class WeightedProgress(
    private val weights: List<Int>,
    private val itemNoun: String = "File",
    private val onProgress: suspend (Progress) -> Unit,
) {
    private val itemCount = weights.size
    private val unitsBefore = weights.runningFold(0L) { acc, weight -> acc + weight }
    private val unitsTotal = unitsBefore.last()
    private var lastFraction = 0f

    /** Emits an initial 0-progress update so the bar appears before any per-item work starts. */
    suspend fun started(detail: String? = null) {
        onProgress(progress(detail ?: "Starting"))
    }

    /** Marks item [index] as the one being processed (its own fraction starts at 0). */
    suspend fun itemStarted(
        index: Int,
        name: String,
    ) {
        emit(index, name, phaseFraction = 0f, phaseDetail = null)
    }

    /** Forwards [progress] for item [index], scaled into the item's share of the bar. */
    suspend fun item(
        index: Int,
        name: String,
        progress: Progress,
    ) {
        emit(index, name, phaseFraction = progress.fraction ?: 0f, phaseDetail = progress.detail)
    }

    /** Emits the terminal 100% update. */
    suspend fun done() {
        lastFraction = 1f
        onProgress(progress("Finishing up"))
    }

    private suspend fun emit(
        index: Int,
        name: String,
        phaseFraction: Float,
        phaseDetail: String?,
    ) {
        val itemShare = phaseFraction.coerceIn(0f, 1f) * weights[index]
        val overall = ((unitsBefore[index] + itemShare) / unitsTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        lastFraction = maxOf(lastFraction, overall)
        val detail =
            buildString {
                append("$itemNoun ${index + 1} of $itemCount — $name")
                phaseDetail?.let { append(": $it") }
            }
        onProgress(progress(detail))
    }

    // Units are derived from the clamped fraction rather than tracked separately, so the counter is
    // exactly as monotonic as the bar it sits next to.
    private fun progress(detail: String): Progress =
        Progress(
            detail = detail,
            fraction = lastFraction,
            processed = (lastFraction * unitsTotal).roundToLong().coerceIn(0L, unitsTotal).toInt(),
            total = unitsTotal.toInt(),
        )
}
