package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvRowLink
import com.moneymanager.domain.model.csv.PdfImportSource
import com.moneymanager.domain.repository.CsvImportReadRepository
import kotlin.time.Instant

interface CsvImportWriteRepository : CsvImportReadRepository {
    /**
     * Creates a new CSV (or Excel) import from parsed data. Creates the metadata, column definitions,
     * and dynamic table with data. When [xlsxBytes] is non-null, also stores the raw workbook bytes
     * (and the worksheet they were parsed from) so the sheet can be re-extracted later if a different
     * worksheet is chosen. When [pdf] is non-null, stores the PDF's bytes and text lines likewise, so a strategy can
     * assemble rows from the lines later.
     *
     * @param fileName The original file name
     * @param headers The column headers
     * @param rows The data rows
     * @return The ID of the created import
     */
    suspend fun createImport(
        fileName: String,
        headers: List<String>,
        rows: List<List<String>>,
        fileChecksum: String,
        fileLastModified: Instant,
        xlsxBytes: ByteArray? = null,
        xlsxWorksheetName: String? = null,
        pdf: PdfImportSource? = null,
    ): CsvImportId

    /**
     * Replaces the staged rows and columns of an existing Excel import in place, keeping its id (and
     * therefore its directory-file link and history) stable. Used when the matched strategy names a
     * different worksheet than the one initially staged: the correct sheet is re-parsed from the stored
     * workbook bytes and re-staged. Recreates the dynamic table, replaces the column metadata, updates
     * the row/column counts, and records the new [worksheetName] on the workbook blob. Any previously
     * written per-row statuses/errors are discarded along with the old rows (the old sheet's rows were
     * never validly imported under this strategy). A PDF import is restaged the same way with the rows its
     * strategy's line rules assembled, and records [pdfRulesHash], the rules that assembled them.
     */
    suspend fun restageImport(
        id: CsvImportId,
        headers: List<String>,
        rows: List<List<String>>,
        worksheetName: String? = null,
        pdfRulesHash: String? = null,
    )

    /**
     * Replaces a staged import's contents in place with a fresh parse of the same file (re-downloaded,
     * possibly re-parsed differently), keeping its id, history and folder link. In one transaction: the
     * dynamic table is recreated with [headers]/[rows], each [carriedRows] entry restores a row's status
     * and transaction link, the file checksum/last-modified are updated, per-row errors are dropped, and
     * the import's provenance rows are renumbered through [rowIndexRemap] (old index -> new index; a
     * provenance row whose old index is absent loses its row reference). [xlsxBytes], when set, replaces
     * the stored workbook, and [pdf], when set, the stored PDF (its [PdfImportSource.stagedRulesHash] naming the
     * rules [rows] were assembled with).
     */
    suspend fun repopulateImport(
        id: CsvImportId,
        headers: List<String>,
        rows: List<List<String>>,
        fileChecksum: String,
        fileLastModified: Instant,
        carriedRows: List<CsvRowLink>,
        rowIndexRemap: Map<Long, Long>,
        xlsxBytes: ByteArray?,
        pdf: PdfImportSource? = null,
    )

    /**
     * Deletes an import, including its metadata, columns, and dynamic table.
     */
    suspend fun deleteImport(id: CsvImportId)

    /**
     * Returns an import to the never-applied state: every row's status and transaction link are
     * cleared, its errors are dropped and its strategy applications are deleted (the application audit
     * trail keeps the history). The caller deletes the transactions themselves.
     */
    suspend fun resetToUnimported(id: CsvImportId)

    /**
     * Marks an import as ignored (or clears the flag). Ignored files are hidden from the actionable
     * Unimported/Imported lists and skipped by "Import all".
     */
    suspend fun setImportIgnored(
        id: CsvImportId,
        ignored: Boolean,
    )

    /**
     * Updates the import status for multiple rows in a single database transaction. A row linked to a
     * transaction it didn't create (any status but IMPORTED) is also recorded as a matched source of it, so
     * re-importing the transaction's own file finds the row from its provenance.
     *
     * @param id The import ID
     * @param status The import status to set for all rows (IMPORTED, DUPLICATE, UPDATED)
     * @param rowTransferMap Map of row index to optional transfer ID to link
     */
    suspend fun updateRowStatusesBatch(
        id: CsvImportId,
        status: String,
        rowTransferMap: Map<Long, TransferId?>,
    )

    /**
     * Clears the import status and transfer link for the given rows so a re-run of the strategy picks
     * them up again (used after their previously-imported transfers were deleted).
     *
     * @param id The import ID
     * @param rowIndexes The row indexes to reset, or null for every row
     */
    suspend fun resetRowStatuses(
        id: CsvImportId,
        rowIndexes: Collection<Long>?,
    )

    /**
     * Saves an error message per row of [errors] (row index to message) in a single database transaction,
     * replacing any existing error for the same row.
     */
    suspend fun saveErrors(
        id: CsvImportId,
        errors: Map<Long, String>,
    )

    /**
     * Clears the error for a specific row (e.g., when re-import succeeds).
     *
     * @param id The import ID
     * @param rowIndex The row index to clear error for
     */
    suspend fun clearError(
        id: CsvImportId,
        rowIndex: Long,
    )

    /**
     * Clears errors for multiple rows in a single database transaction.
     *
     * @param id The import ID
     * @param rowIndexes The row indexes to clear errors for
     */
    suspend fun clearErrors(
        id: CsvImportId,
        rowIndexes: Collection<Long>,
    )

    /**
     * Records one successful strategy application in the CSV import history.
     * The latest application metadata shown to users is derived when querying imports.
     *
     * @param id The import ID
     * @param strategyId The strategy used for this application
     * @param strategyName The strategy name snapshot shown to users
     * @param appliedAt When this strategy application completed successfully
     */
    suspend fun recordImportApplication(
        id: CsvImportId,
        strategyId: CsvImportStrategyId,
        strategyName: String,
        appliedAt: Instant,
    )
}
