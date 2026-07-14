@file:OptIn(ExperimentalTime::class)

package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.CsvImportReadRepository
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface CsvImportWriteRepository : CsvImportReadRepository {
    /**
     * Creates a new CSV import from parsed data.
     * Creates the metadata, column definitions, and dynamic table with data.
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
    ): CsvImportId

    /**
     * Deletes an import, including its metadata, columns, and dynamic table.
     */
    suspend fun deleteImport(id: CsvImportId)

    /**
     * Marks an import as ignored (or clears the flag). Ignored files are hidden from the actionable
     * Unimported/Imported lists and skipped by "Import all".
     */
    suspend fun setImportIgnored(
        id: CsvImportId,
        ignored: Boolean,
    )

    /**
     * Updates the transfer ID for a specific row after importing.
     * This links the CSV row to the created transfer for navigation.
     *
     * @param id The import ID
     * @param rowIndex The row index to update
     * @param transferId The transfer ID to link
     */
    suspend fun updateRowTransferId(
        id: CsvImportId,
        rowIndex: Long,
        transferId: TransferId,
    )

    /**
     * Updates transfer IDs for multiple rows in batch.
     *
     * @param id The import ID
     * @param rowTransferMap Map of row index to transfer ID
     */
    suspend fun updateRowTransferIdsBatch(
        id: CsvImportId,
        rowTransferMap: Map<Long, TransferId>,
    )

    /**
     * Updates the import status for a specific row.
     *
     * @param id The import ID
     * @param rowIndex The row index to update
     * @param status The import status (IMPORTED, DUPLICATE, UPDATED)
     * @param transferId Optional transfer ID to link
     */
    suspend fun updateRowStatus(
        id: CsvImportId,
        rowIndex: Long,
        status: String,
        transferId: TransferId? = null,
    )

    /**
     * Updates the import status for multiple rows in a single database transaction.
     * Much faster than per-row [updateRowStatus] calls for large imports.
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
     * @param rowIndexes The row indexes to reset
     */
    suspend fun resetRowStatuses(
        id: CsvImportId,
        rowIndexes: Collection<Long>,
    )

    /**
     * Saves an error message for a specific row.
     * Replaces any existing error for the same row.
     *
     * @param id The import ID
     * @param rowIndex The row index that failed
     * @param errorMessage The error message to save
     */
    suspend fun saveError(
        id: CsvImportId,
        rowIndex: Long,
        errorMessage: String,
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
