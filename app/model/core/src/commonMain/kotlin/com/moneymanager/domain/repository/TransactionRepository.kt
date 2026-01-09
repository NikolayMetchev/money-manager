@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface TransactionRepository {
    fun getTransactionById(id: Long): Flow<Transfer?>

    fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>>

    fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>>

    fun getTransactionsByAccountAndDateRange(
        accountId: AccountId,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>>

    fun getAccountBalances(): Flow<List<AccountBalance>>

    suspend fun getRunningBalanceByAccountPaginated(
        accountId: AccountId,
        pageSize: Int,
        pagingInfo: PagingInfo?,
    ): PagingResult<AccountRow>

    /**
     * Loads older transactions (backward pagination - items that come before the current first item).
     * Used when user scrolls up after navigating to a transaction in the middle of the list.
     *
     * @param accountId The account to load transactions for
     * @param pageSize The number of transactions to load
     * @param firstTimestamp Timestamp of the first item in the current list
     * @param firstId ID of the first item in the current list
     * @return A PagingResult containing items to prepend (in correct display order)
     */
    suspend fun getRunningBalanceByAccountPaginatedBackward(
        accountId: AccountId,
        pageSize: Int,
        firstTimestamp: Instant,
        firstId: TransactionId,
    ): PagingResult<AccountRow>

    /**
     * Loads a page of transactions centered around a specific transaction.
     * Tries to load equal amounts before and after the target transaction.
     * If there aren't enough on one side, loads more from the other side.
     *
     * @param accountId The account to load transactions for
     * @param transactionId The transaction to center the page around
     * @param pageSize The number of transactions to load
     * @return A PagingResult containing the transactions and the index of the target transaction within the page
     */
    suspend fun getPageContainingTransaction(
        accountId: AccountId,
        transactionId: TransferId,
        pageSize: Int,
    ): PageWithTargetIndex<AccountRow>

    /**
     * Creates transfers with their attributes and sources in a single atomic operation.
     * Works for single transfers (from UI), batch operations (sample data), and CSV imports.
     *
     * @param transfers List of transfers to create
     * @param newAttributes Map of transfer ID to attributes to create (attributes don't have IDs yet)
     * @param sourceRecorder Strategy for recording source information (includes device info)
     * @param onProgress Optional callback for batch progress (called after each batch of ~1000)
     */
    suspend fun createTransfers(
        transfers: List<Transfer>,
        newAttributes: Map<TransferId, List<NewAttribute>> = emptyMap(),
        sourceRecorder: SourceRecorder,
        onProgress: (suspend (created: Int, total: Int) -> Unit)? = null,
    )

    /**
     * Updates a transfer and its attributes atomically, creating only ONE revision bump.
     *
     * This method handles the case where both transfer fields AND attributes change.
     * Without this method, updating the transfer would bump revision (1→2), and then
     * each attribute change would bump it again (2→3, etc.).
     *
     * With this method:
     * - If only transfer changes: bumps to rev 2
     * - If only attributes change: bumps to rev 2
     * - If BOTH change: bumps to rev 2 (not 3+)
     *
     * @param transfer The updated transfer (null if transfer fields didn't change)
     * @param deletedAttributeIds IDs of attributes to delete
     * @param updatedAttributes Map of attribute ID to (typeId, value) for updates
     * @param newAttributes List of (typeId, value) pairs for new attributes
     * @param transactionId The transaction ID (needed when transfer is null)
     */
    suspend fun updateTransfer(
        transfer: Transfer?,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        transactionId: TransferId,
    )

    /**
     * Bumps the revision of a transfer without changing any other fields.
     * This is used when only attributes change, to create an audit entry.
     *
     * @param id The transfer ID
     * @return The new revision ID
     */
    suspend fun bumpRevisionOnly(id: TransferId): Long

    suspend fun deleteTransaction(id: Long)
}
