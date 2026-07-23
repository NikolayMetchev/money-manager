package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.XlsxImportBlob
import kotlinx.coroutines.flow.Flow

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
     * Ids of the accounts this import auto-created (from CSV provenance). Used by re-import to
     * scope account merges/deletions to import-created accounts only.
     */
    suspend fun getAccountsCreatedByImport(id: CsvImportId): Set<AccountId>

    /**
     * Finds imports that match the given file checksum.
     *
     * @param checksum The SHA-256 checksum to search for
     * @return List of matching imports, ordered by timestamp descending
     */
    suspend fun findImportsByChecksum(checksum: String): List<CsvImport>

    /** The raw workbook bytes for an Excel-backed import, or null for a CSV/QIF import. */
    suspend fun getXlsxBlob(id: CsvImportId): XlsxImportBlob?

    /**
     * For every CSV import that has one, the single account appearing on either side of every one of
     * its currently-existing created transfers — i.e. the account the file was actually imported
     * against last time. Lets re-import auto-resolve a source-account override for strategies with no
     * SOURCE_ACCOUNT mapping (e.g. Monzo CSV) without asking the user again for a file already
     * successfully imported once.
     */
    suspend fun historicalSourceAccounts(): Map<CsvImportId, AccountId>
}
