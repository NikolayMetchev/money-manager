package com.moneymanager.tasks

/**
 * Folds the phases of one run (an API import or re-import, say) into a single monotonic 0..1 bar.
 *
 * A run is a sequence of phases of very different cost — parsing responses, resolving assets, the
 * engine write — and several of them emit their own independent 0..1 sweep. Each phase is given a
 * slice of the bar (`base`..`base + span`) that its inner fraction scales, and emissions are
 * clamped non-decreasing so a later phase starting its own sweep at 0 never rewinds the bar. [WeightedProgress]
 * does the same across the items of a bulk run.
 *
 * A null sink makes every call a no-op, so callers can instrument unconditionally.
 */
class PhasedProgress(
    private val onProgress: (suspend (Progress) -> Unit)?,
) {
    private var lastFraction = 0f

    /** Reports [detail] at [base] + [span] * [innerFraction] of the overall bar. */
    suspend fun emit(
        base: Float,
        span: Float = 0f,
        detail: String,
        innerFraction: Float? = null,
        processed: Int? = null,
        total: Int? = null,
    ) {
        val sink = onProgress ?: return
        val scaled = (base + span * (innerFraction ?: 0f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        lastFraction = maxOf(lastFraction, scaled)
        sink(Progress(detail, fraction = lastFraction, processed = processed, total = total))
    }

    /**
     * A sink that folds another producer's own 0..1 sweep into this bar's [base]..[base] + [span]
     * slice. [detail], when given, replaces the inner detail — useful where the inner wording
     * describes the mechanism rather than what the user asked for (the engine reports "Importing
     * transactions" while it is deleting them).
     */
    fun sink(
        base: Float,
        span: Float,
        detail: String? = null,
    ): (suspend (Progress) -> Unit)? =
        if (onProgress == null) {
            null
        } else {
            { inner ->
                emit(
                    base = base,
                    span = span,
                    detail = detail ?: inner.detail,
                    innerFraction = inner.fraction,
                    processed = inner.processed,
                    total = inner.total,
                )
            }
        }
}
