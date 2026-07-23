package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.qif.QifImportRecord
import kotlinx.coroutines.flow.Flow

/**
 * Read access for QIF imports. QIF imports reuse the CSV import-strategy engine
 * (see [CsvImportStrategyReadRepository]); this repository only stores the raw parsed file
 * and links records back to the transfers they produced.
 */
interface QifImportReadRepository {
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

    /** Finds imports that match the given file checksum, newest first. */
    suspend fun findImportsByChecksum(checksum: String): List<QifImport>

    /**
     * Accounts this import auto-created (its provenance points at them), used by re-import to scope
     * duplicate-account merges and empty-account cleanup to accounts the import itself introduced.
     */
    suspend fun getAccountsCreatedByImport(id: QifImportId): Set<AccountId>
}
