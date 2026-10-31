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
 * Each emission carries the overall fraction, the items' weighted units done/total (rows) as
 * [Progress.processed]/[Progress.total], and a detail naming the item: `File 2 of 5 — b.csv: phase`.
 *
 * [preparation] weights the steps a run takes before its first item (staging each PDF a bulk import must
 * assemble rows for, say), which come first on the bar. They may finish in any order (they can run in
 * parallel), and [prepared] reports each as it does: `Staged 2 of 3 PDFs — b.pdf`.
 */
class WeightedProgress(
    private val weights: List<Int>,
    private val itemNoun: String = "File",
    private val preparation: List<Int> = emptyList(),
    private val preparationNoun: String = "steps",
    private val onProgress: suspend (Progress) -> Unit,
) {
    private val itemCount = weights.size
    private val preparationUnits = preparation.sumOf { it.toLong() }
    private var preparedUnits = 0L
    private var preparedCount = 0
    private val unitsBefore = weights.runningFold(preparationUnits) { acc, weight -> acc + weight }
    private val unitsTotal = unitsBefore.last()
    private var lastFraction = 0f

    /** Emits an initial 0-progress update so the bar appears before any per-item work starts. */
    suspend fun started(detail: String? = null) {
        onProgress(progress(detail ?: "Starting"))
    }

    /** Credits preparation step [index], named [name], as done; [verb] says what was done (`Staged`). */
    suspend fun prepared(
        index: Int,
        verb: String,
        name: String,
    ) {
        preparedUnits += preparation[index]
        preparedCount++
        advanceTo(preparedUnits)
        onProgress(progress("$verb $preparedCount of ${preparation.size} $preparationNoun — $name"))
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
        advanceTo(unitsBefore[index] + phaseFraction.coerceIn(0f, 1f) * weights[index])
        val detail =
            buildString {
                append("$itemNoun ${index + 1} of $itemCount — $name")
                phaseDetail?.let { append(": $it") }
            }
        onProgress(progress(detail))
    }

    private fun advanceTo(unitsDone: Float) {
        val overall = (unitsDone / unitsTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        lastFraction = maxOf(lastFraction, overall)
    }

    private fun advanceTo(unitsDone: Long) = advanceTo(unitsDone.toFloat())

    // Units are derived from the clamped fraction rather than tracked separately, so the counter is
    // exactly as monotonic as the bar it sits next to. They count the items' units (rows) only: preparation
    // takes its share of the bar, but it isn't more rows.
    private fun progress(detail: String): Progress {
        val itemUnits = unitsTotal - preparationUnits
        return Progress(
            detail = detail,
            fraction = lastFraction,
            processed = (lastFraction * itemUnits).roundToLong().coerceIn(0L, itemUnits).toInt(),
            total = itemUnits.toInt(),
        )
    }
}
