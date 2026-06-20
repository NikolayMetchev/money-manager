@file:OptIn(ExperimentalTime::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import kotlinx.coroutines.flow.Flow
import kotlin.time.ExperimentalTime

interface CsvImportReadRepository {
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
     * Finds imports that match the given file checksum.
     *
     * @param checksum The SHA-256 checksum to search for
     * @return List of matching imports, ordered by timestamp descending
     */
    suspend fun findImportsByChecksum(checksum: String): List<CsvImport>
}
