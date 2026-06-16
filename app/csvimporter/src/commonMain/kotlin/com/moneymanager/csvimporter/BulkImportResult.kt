package com.moneymanager.csvimporter

/**
 * Outcome of a bulk import run across many files (CSV or QIF). Both flows produce identical summary
 * text, so [toSummary] lives here and the format stays in one place.
 */
interface BulkImportResult {
    val filesImported: Int
    val transfersCreated: Int
    val duplicatesSkipped: Int
    val filesSkippedNoStrategy: Int
    val filesFailed: Int

    fun toSummary(): String =
        buildString {
            append("Imported $filesImported file${if (filesImported == 1) "" else "s"}")
            append(" · $transfersCreated new")
            if (duplicatesSkipped > 0) append(" · $duplicatesSkipped duplicates skipped")
            if (filesSkippedNoStrategy > 0) append(" · $filesSkippedNoStrategy skipped (no strategy)")
            if (filesFailed > 0) append(" · $filesFailed failed")
        }
}
