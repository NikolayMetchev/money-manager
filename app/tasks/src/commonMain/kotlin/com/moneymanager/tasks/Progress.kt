package com.moneymanager.tasks

/**
 * The one progress shape every long-running operation reports: imports, downloads, database startup,
 * cloud sync and background tasks.
 *
 * @property fraction Completion in [0, 1], when known.
 * @property processed Units of work done so far (rows, files, steps), when known.
 * @property total Units of work in all, when known.
 * @property item Which of several items (files of a bulk import) the work is on, for a view that lays the
 *   counter, the item's name and its current step out on lines of their own. [detail] still says all of it on one
 *   line, for views that show a single line.
 */
data class Progress(
    val detail: String,
    val fraction: Float? = null,
    val processed: Int? = null,
    val total: Int? = null,
    val item: Item? = null,
) {
    /**
     * @property done Items finished so far.
     * @property count Items in all.
     * @property noun What the items are, plural ("files").
     * @property name The item being worked on, if any.
     * @property step What is being done to it right now.
     */
    data class Item(
        val done: Int,
        val count: Int,
        val noun: String,
        val name: String?,
        val step: String,
    )

    companion object {
        /** Progress counted in whole units: [done] of [total], with no fraction while [total] is still 0. */
        fun counted(
            detail: String,
            done: Int,
            total: Int,
        ): Progress =
            Progress(
                detail = detail,
                fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
                processed = done,
                total = total,
            )
    }
}
