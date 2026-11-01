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
 * [Progress.processed]/[Progress.total], and a detail naming the item: `File 2 of 5 — b.csv: phase`, which
 * [Progress.item] also carries in parts (files finished, the file, the phase; [itemsNoun] names the items).
 *
 * [preparation] weights the steps a run takes before its first item (staging each PDF a bulk import must
 * assemble rows for, say), which come first on the bar. They may finish in any order (they can run in
 * parallel), and [prepared] reports each as it does: `Staged 2 of 3 PDFs — b.pdf`.
 */
class WeightedProgress(
    private val weights: List<Int>,
    private val itemNoun: String = "File",
    private val itemsNoun: String = "${itemNoun.lowercase()}s",
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
    private val itemUnits = unitsTotal - preparationUnits
    private var lastFraction = 0f
    private var itemUnitsDone = 0f

    /** Emits an initial 0-progress update so the bar appears before any per-item work starts. */
    suspend fun started(detail: String? = null) {
        val step = detail ?: "Starting"
        onProgress(progress(step, Progress.Item(0, itemCount, itemsNoun, name = null, step = step)))
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
        onProgress(
            progress(
                "$verb $preparedCount of ${preparation.size} $preparationNoun — $name",
                Progress.Item(preparedCount, preparation.size, preparationNoun, name, step = "Preparing $preparationNoun"),
            ),
        )
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

    /**
     * Emits the terminal update: 100% once every item ran, or, for a run stopped after [itemsDone] items, the counts
     * it actually reached, so a stopped run never claims the files it didn't start.
     */
    suspend fun done(itemsDone: Int = itemCount) {
        if (itemsDone >= itemCount) {
            lastFraction = 1f
            itemUnitsDone = itemUnits.toFloat()
        } else {
            // Every started item has ended by now (skipped and failed ones never report reaching their end).
            advanceTo(unitsBefore[itemsDone.coerceAtLeast(0)].toFloat())
        }
        val item = Progress.Item(minOf(itemsDone, itemCount), itemCount, itemsNoun, name = null, step = "Finishing up")
        onProgress(progress("Finishing up", item))
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
        onProgress(progress(detail, Progress.Item(index, itemCount, itemsNoun, name, step = phaseDetail ?: "Starting")))
    }

    private fun advanceTo(unitsDone: Float) {
        val overall = (unitsDone / unitsTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        lastFraction = maxOf(lastFraction, overall)
        itemUnitsDone = maxOf(itemUnitsDone, unitsDone - preparationUnits)
    }

    private fun advanceTo(unitsDone: Long) = advanceTo(unitsDone.toFloat())

    // The counter counts the items' units (rows) only, clamped monotonic like the bar: preparation takes its
    // share of the bar, but it isn't more rows, so the counter stays at 0 until the first item's rows move.
    private fun progress(
        detail: String,
        item: Progress.Item,
    ): Progress =
        Progress(
            detail = detail,
            fraction = lastFraction,
            processed = itemUnitsDone.roundToLong().coerceIn(0L, itemUnits).toInt(),
            total = itemUnits.toInt(),
            item = item,
        )
}
