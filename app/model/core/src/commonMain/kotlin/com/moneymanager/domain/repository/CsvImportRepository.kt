@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface CsvImportRepository {
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
        fileChecksum: String? = null,
        fileLastModified: Instant? = null,
    ): CsvImportId

    /**
     * Gets all imports, ordered by timestamp descending (newest first).
     */
    fun getAllImports(): Flow<List<CsvImport>>

    /**
     * Gets a single import by ID.
     */
    fun getImport(id: CsvImportId): Flow<CsvImport?>

    /**
     * Gets rows from the import's dynamic table with pagination.
     *
     * @param id The import ID
     * @param limit Maximum number of rows to return
     * @param offset Number of rows to skip
     * @return List of CSV rows
     */
    suspend fun getImportRows(
        id: CsvImportId,
        limit: Int,
        offset: Int,
    ): List<CsvRow>

    /**
     * Deletes an import, including its metadata, columns, and dynamic table.
     */
    suspend fun deleteImport(id: CsvImportId)

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
     * Finds imports that match the given file checksum.
     *
     * @param checksum The SHA-256 checksum to search for
     * @return List of matching imports, ordered by timestamp descending
     */
    suspend fun findImportsByChecksum(checksum: String): List<CsvImport>
}
