package com.moneymanager.importer

import com.moneymanager.tasks.Progress

/**
 * Step [done] of [steps] of a stage that has no unit to count, as a fraction of the bar only: [detail] carries its own
 * counts, so a view showing processed/total doesn't read the steps as rows.
 */
internal fun stepProgress(
    detail: String,
    done: Int,
    steps: Int,
): Progress = Progress(detail, fraction = (done.toFloat() / steps).coerceIn(0f, 1f))
