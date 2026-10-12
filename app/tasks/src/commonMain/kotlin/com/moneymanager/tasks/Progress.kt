package com.moneymanager.tasks

/**
 * The one progress shape every long-running operation reports: imports, downloads, database startup,
 * cloud sync and background tasks.
 *
 * @property fraction Completion in [0, 1], when known.
 * @property processed Units of work done so far (rows, files, steps), when known.
 * @property total Units of work in all, when known.
 */
data class Progress(
    val detail: String,
    val fraction: Float? = null,
    val processed: Int? = null,
    val total: Int? = null,
) {
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
