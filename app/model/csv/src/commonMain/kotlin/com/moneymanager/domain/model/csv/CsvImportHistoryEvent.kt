package com.moneymanager.domain.model.csv

import kotlin.time.Instant

/** One entry in a CSV file's import history, read from the strategy-application audit trail. */
sealed interface CsvImportHistoryEvent {
    val at: Instant

    /** A strategy was applied to the file (an import or re-import). */
    data class Applied(
        val strategyName: String,
        override val at: Instant,
    ) : CsvImportHistoryEvent

    /** The file was unimported: the transactions it created were deleted and its applications cleared. */
    data class Unimported(
        override val at: Instant,
    ) : CsvImportHistoryEvent
}
