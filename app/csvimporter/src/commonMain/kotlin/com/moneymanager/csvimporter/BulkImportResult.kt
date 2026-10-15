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

/** What a bulk apply did with one file. */
enum class BulkFileStatus {
    IMPORTED,

    /** The file had no rows left to import. */
    NOTHING_TO_IMPORT,

    /** No strategy matched it, or its strategy needs a source account and none was given. */
    SKIPPED_NO_STRATEGY,
    FAILED,
}

/** One file's part of a [BulkApplyResult]; [fileId] is its staged CSV or QIF import id. */
data class BulkFileOutcome(
    val fileId: String,
    val fileName: String,
    val status: BulkFileStatus,
    val strategyName: String? = null,
    val transfersCreated: Int = 0,
    val duplicatesSkipped: Int = 0,
    val error: String? = null,
)

/** A bulk CSV or QIF apply, file by file; the counts are derived from [files]. */
data class BulkApplyResult(
    val files: List<BulkFileOutcome>,
) : BulkImportResult {
    override val filesImported: Int get() = files.count { it.status == BulkFileStatus.IMPORTED }
    override val transfersCreated: Int get() = files.sumOf { it.transfersCreated }
    override val duplicatesSkipped: Int get() = files.sumOf { it.duplicatesSkipped }
    override val filesSkippedNoStrategy: Int get() = files.count { it.status == BulkFileStatus.SKIPPED_NO_STRATEGY }
    override val filesFailed: Int get() = files.count { it.status == BulkFileStatus.FAILED }
}
