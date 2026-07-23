package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.repository.QifImportReadRepository
import kotlin.time.Instant

interface QifImportWriteRepository : QifImportReadRepository {
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

    /** Deletes an import, including its records. */
    suspend fun deleteImport(id: QifImportId)

    /**
     * Marks an import as ignored (or clears the flag). Ignored files are hidden from the actionable
     * Unimported/Imported lists and skipped by "Import all".
     */
    suspend fun setImportIgnored(
        id: QifImportId,
        ignored: Boolean,
    )

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
}
