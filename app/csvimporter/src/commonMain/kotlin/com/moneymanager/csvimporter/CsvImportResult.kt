package com.moneymanager.csvimporter

/**
 * Result of a CSV import operation.
 */
data class CsvImportResult(
    val successCount: Int,
    val failedRows: List<FailedRow>,
    val duplicateCount: Int = 0,
) {
    data class FailedRow(
        val rowIndex: Long,
        val errorMessage: String,
    )
}
