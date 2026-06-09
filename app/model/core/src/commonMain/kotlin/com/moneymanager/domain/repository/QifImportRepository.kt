@file:OptIn(ExperimentalTime::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.model.qif.QifImportRecord
import kotlinx.coroutines.flow.Flow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Repository for QIF imports. QIF imports reuse the CSV import-strategy engine
 * (see [CsvImportStrategyRepository]); this repository only stores the raw parsed file
 * and links records back to the transfers they produced.
 */
interface QifImportRepository {
    /**
     * Creates a new QIF import from parsed records.
     *
     * @param accountType The dominant section/account type (e.g. "BANK"), used for strategy matching.
     * @return The ID of the created import.
     */
    suspend fun createImport(
        fileName: String,
        records: List<QifImportRecord>,
        accountType: String,
        fileChecksum: String,
        fileLastModified: Instant,
    ): QifImportId

    /** Gets all imports, ordered by timestamp descending (newest first). */
    fun getAllImports(): Flow<List<QifImport>>

    /** Gets a single import by ID. */
    fun getImport(id: QifImportId): Flow<QifImport?>

    /** Gets parsed records with pagination, ordered by record index. */
    suspend fun getImportRecords(
        id: QifImportId,
        limit: Int,
        offset: Int,
    ): List<QifImportRecord>

    /** Total number of records in the import. */
    suspend fun countRecords(id: QifImportId): Int

    /** Deletes an import, including its records. */
    suspend fun deleteImport(id: QifImportId)

    /**
     * Updates the import status (and optional linked transfer) for multiple records in a single
     * database transaction.
     */
    suspend fun updateRecordStatusesBatch(
        id: QifImportId,
        status: String,
        recordTransferMap: Map<Long, TransferId?>,
    )

    /** Saves an error message for a specific record, replacing any existing one. */
    suspend fun saveError(
        id: QifImportId,
        recordIndex: Long,
        errorMessage: String,
    )

    /** Clears errors for multiple records in a single database transaction. */
    suspend fun clearErrors(
        id: QifImportId,
        recordIndexes: Collection<Long>,
    )

    /**
     * Records one successful strategy application in the QIF import history.
     * QIF reuses CSV strategies, hence [CsvImportStrategyId].
     */
    suspend fun recordImportApplication(
        id: QifImportId,
        strategyId: CsvImportStrategyId,
        strategyName: String,
        appliedAt: Instant,
    )

    /** Finds imports that match the given file checksum, newest first. */
    suspend fun findImportsByChecksum(checksum: String): List<QifImport>
}
