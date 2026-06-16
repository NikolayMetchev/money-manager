package com.moneymanager.qifimporter

/** Result of a QIF import run. */
data class QifImportResult(
    val successCount: Int,
    val duplicateCount: Int = 0,
    val failedCount: Int = 0,
)
