package com.moneymanager.domain.repository

import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import kotlinx.coroutines.flow.Flow

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
}
