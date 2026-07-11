@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferMissingCompanion
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface TransactionReadRepository {
    fun getTransactionById(id: Long): Flow<Transfer?>

    /**
     * Batch lookup of transfers (with attributes) by id, for callers that would otherwise issue one
     * [getTransactionById] round trip per id — e.g. the re-import planner diffing thousands of
     * already-imported rows. Ids that no longer resolve are simply absent from the result.
     */
    suspend fun getTransactionsByIds(ids: Collection<TransferId>): Map<TransferId, Transfer>

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

    /**
     * Finds transfers matched by a companion transaction rule that have no companion yet:
     * transfers carrying an attribute named [matchAttributeName] whose value matches the
     * SQL LIKE [matchValuePattern], where no transfer carries an attribute named
     * [linkAttributeName] with that same value.
     */
    fun getTransfersMissingCompanion(
        matchAttributeName: String,
        matchValuePattern: String,
        linkAttributeName: String,
    ): Flow<List<TransferMissingCompanion>>

    /**
     * Directed transfers [sourceAccountId] → [targetAccountId] of exactly [amount] at or before
     * [maxTimestamp] that are not yet the target (id2) of a [reversalTypeId] relationship, newest
     * first. Used by the import engine to pair a refund/cancellation with the movement it reverses.
     */
    suspend fun getUnreversedTransfersBetween(
        sourceAccountId: AccountId,
        targetAccountId: AccountId,
        amount: Money,
        maxTimestamp: Instant,
        reversalTypeId: RelationshipTypeId,
    ): List<Transfer>

    /**
     * @param currencyId When set, only transactions in this currency are returned (and counted for paging).
     */
    suspend fun getRunningBalanceByAccountPaginated(
        accountId: AccountId,
        pageSize: Int,
        pagingInfo: PagingInfo?,
        currencyId: CurrencyId? = null,
    ): PagingResult<AccountRow>

    /**
     * Loads older transactions (backward pagination - items that come before the current first item).
     * Used when user scrolls up after navigating to a transaction in the middle of the list.
     *
     * @param accountId The account to load transactions for
     * @param pageSize The number of transactions to load
     * @param firstTimestamp Timestamp of the first item in the current list
     * @param firstId ID of the first item in the current list
     * @param currencyId When set, only transactions in this currency are returned (and counted for paging)
     * @return A PagingResult containing items to prepend (in correct display order)
     */
    suspend fun getRunningBalanceByAccountPaginatedBackward(
        accountId: AccountId,
        pageSize: Int,
        firstTimestamp: Instant,
        firstId: TransactionId,
        currencyId: CurrencyId? = null,
    ): PagingResult<AccountRow>

    /**
     * Loads a page of transactions centered around a specific transaction.
     * Tries to load equal amounts before and after the target transaction.
     * If there aren't enough on one side, loads more from the other side.
     *
     * @param accountId The account to load transactions for
     * @param transactionId The transaction to center the page around
     * @param pageSize The number of transactions to load
     * @param currencyId When set, positions and pages within only this currency's transactions
     * @return A PagingResult containing the transactions and the index of the target transaction within the page
     */
    suspend fun getPageContainingTransaction(
        accountId: AccountId,
        transactionId: TransferId,
        pageSize: Int,
        currencyId: CurrencyId? = null,
    ): PageWithTargetIndex<AccountRow>
}
