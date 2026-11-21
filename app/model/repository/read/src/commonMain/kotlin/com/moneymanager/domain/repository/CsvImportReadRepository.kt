package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.PdfImportSource
import com.moneymanager.domain.model.csv.PdfTextLine
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
     * Ids of the accounts this import auto-created (from CSV provenance). Re-import deletes those it
     * leaves empty, and never any other account.
     */
    suspend fun getAccountsCreatedByImport(id: CsvImportId): Set<AccountId>

    /**
     * Ids of the still-existing transfers this import created (not merely updated), including the fee
     * and pass-through legs its rows produced. Used by unimport to find everything the file booked.
     */
    suspend fun getTransferIdsCreatedByImport(id: CsvImportId): Set<TransferId>

    /** The account whose statement (or API feed) each of [ids] came from, for those an import recorded one (its own account). */
    suspend fun getStatementAccounts(ids: Collection<TransferId>): Map<TransferId, AccountId>

    /** Ids of the still-existing trades this import created. See [getTransferIdsCreatedByImport]. */
    suspend fun getTradeIdsCreatedByImport(id: CsvImportId): Set<TradeId>

    /**
     * The transfers and trades the records of [id] observe that a staged file or the match created, whichever file that
     * was. One a person, an API session or a reconciliation copy created is not the file's to delete.
     */
    suspend fun getTransactionsObservedByImport(id: CsvImportId): Set<TransactionId>

    /**
     * Which of [transactionIds] something other than the files [importIds] still observes: another file's or an API
     * session's record, a person, or a leg group none of whose records those files hold. Unimporting the files leaves these.
     */
    suspend fun getTransactionsObservedOutsideImports(
        importIds: Collection<CsvImportId>,
        transactionIds: Collection<TransactionId>,
    ): Set<TransactionId>

    /**
     * Finds imports that match the given file checksum.
     *
     * @param checksum The SHA-256 checksum to search for
     * @return List of matching imports, ordered by timestamp descending
     */
    suspend fun findImportsByChecksum(checksum: String): List<CsvImport>

    /** The raw workbook bytes for an Excel-backed import, or null for a CSV/QIF import. */
    suspend fun getXlsxBlob(id: CsvImportId): XlsxImportBlob?

    /** The file and its text lines for a PDF-backed import, or null for any other. */
    suspend fun getPdfSource(id: CsvImportId): PdfImportSource?

    /**
     * The text lines of a PDF-backed import, or null for any other: what [getPdfSource] returns without the file's
     * bytes, which are far larger and which matching a strategy or showing the lines never reads.
     */
    suspend fun getPdfLines(id: CsvImportId): List<PdfTextLine>?

    /** The imports staged from a PDF, which only a PDF strategy reads. */
    suspend fun getPdfImportIds(): Set<CsvImportId>
}
