package com.moneymanager.csvimporter

import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId

/**
 * Result of a CSV import operation. [createdTransferIds] are the transfers its rows booked, which a later match sync can
 * fold into another record's transaction ([foldedInto]).
 */
data class CsvImportResult(
    val successCount: Int,
    val failedRows: List<FailedRow>,
    val duplicateCount: Int = 0,
    val createdTransferIds: Set<TransferId> = emptySet(),
) {
    /** This result once a match sync deleted [gone]: a row booked only to be folded into a transaction another record made is a duplicate. */
    fun foldedInto(gone: Set<TransactionId>): CsvImportResult {
        val folded = createdTransferIds.count { it in gone }
        return copy(
            successCount = successCount - folded,
            duplicateCount = duplicateCount + folded,
            createdTransferIds = createdTransferIds.filterTo(mutableSetOf()) { it !in gone },
        )
    }

    data class FailedRow(
        val rowIndex: Long,
        val errorMessage: String,
    )
}
