@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface TransactionRepository {
    fun getTransactionById(id: Uuid): Flow<Transfer?>

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
     * @param transfersWithAttributes List of transfers with their (attributeTypeId, value) pairs
     * @param sourceRecorder Strategy for recording source information
     * @param deviceInfo Device info for source recording
     * @param onProgress Optional callback for batch progress (called after each batch of ~1000)
     */
    suspend fun createTransfersWithAttributesAndSources(
        transfersWithAttributes: List<Pair<Transfer, List<Pair<AttributeTypeId, String>>>>,
        sourceRecorder: SourceRecorder,
        deviceInfo: DeviceInfo,
        onProgress: (suspend (created: Int, total: Int) -> Unit)? = null,
    )

    suspend fun updateTransfer(transfer: Transfer)

    /**
     * Bumps the revision of a transfer without changing any other fields.
     * This is used when only attributes change, to create an audit entry.
     *
     * @param id The transfer ID
     * @return The new revision ID
     */
    suspend fun bumpRevisionOnly(id: TransferId): Long

    suspend fun deleteTransaction(id: Uuid)
}
