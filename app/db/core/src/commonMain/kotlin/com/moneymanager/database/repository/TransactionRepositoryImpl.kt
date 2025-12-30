@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.AccountBalanceMapper
import com.moneymanager.database.mapper.AccountRowMapper
import com.moneymanager.database.mapper.TransferMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TransactionRepositoryImpl(
    database: MoneyManagerDatabase,
) : TransactionRepository {
    private val transferQueries = database.transferQueries
    private val transactionIdQueries = database.transactionIdQueries

    override fun getTransactionById(id: Uuid): Flow<Transfer?> =
        transferQueries.selectById(id.toString(), TransferMapper::mapRaw)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)

    override fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>> =
        transferQueries.selectByAccount(accountId.id, accountId.id, TransferMapper::mapRaw)
            .asFlow()
            .mapToList(Dispatchers.Default)

    override fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries.selectByDateRange(
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds(),
            TransferMapper::mapRaw,
        )
            .asFlow()
            .mapToList(Dispatchers.Default)

    override fun getTransactionsByAccountAndDateRange(
        accountId: AccountId,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries.selectByAccountAndDateRange(
            accountId.id,
            accountId.id,
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds(),
            TransferMapper::mapRaw,
        )
            .asFlow()
            .mapToList(Dispatchers.Default)

    override fun getAccountBalances(): Flow<List<AccountBalance>> =
        transferQueries.selectAllBalances()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(AccountBalanceMapper::mapList)

    override suspend fun getRunningBalanceByAccountPaginated(
        accountId: AccountId,
        pageSize: Int,
        pagingInfo: PagingInfo?,
    ): PagingResult<AccountRow> =
        withContext(Dispatchers.Default) {
            val items =
                transferQueries.selectRunningBalanceByAccountPaginated(
                    accountId.id,
                    pagingInfo?.lastTimestamp?.toEpochMilliseconds(),
                    pagingInfo?.lastId?.toString(),
                    (pageSize + 1).toLong(),
                ) { id, timestamp, description, accountId_, currencyId, transactionAmount, runningBalance,
                    currency_id, currency_code, currency_name, currency_scaleFactor, sourceAccountId, targetAccountId,
                    ->
                    AccountRowMapper.mapRaw(
                        id,
                        timestamp,
                        description,
                        accountId_,
                        currencyId,
                        transactionAmount,
                        runningBalance,
                        currency_id,
                        currency_code,
                        currency_name,
                        currency_scaleFactor,
                        sourceAccountId,
                        targetAccountId,
                    )
                }
                    .executeAsList()

            val hasMore = items.size > pageSize
            val pageItems = if (hasMore) items.take(pageSize) else items

            val nextPagingInfo =
                if (hasMore && pageItems.isNotEmpty()) {
                    val lastItem = pageItems.last()
                    PagingInfo(
                        lastTimestamp = lastItem.timestamp,
                        lastId = lastItem.transactionId,
                        hasMore = true,
                    )
                } else {
                    PagingInfo(
                        lastTimestamp = null,
                        lastId = null,
                        hasMore = false,
                    )
                }

            PagingResult(
                items = pageItems,
                pagingInfo = nextPagingInfo,
            )
        }

    override suspend fun getRunningBalanceByAccountPaginatedBackward(
        accountId: AccountId,
        pageSize: Int,
        firstTimestamp: Instant,
        firstId: TransactionId,
    ): PagingResult<AccountRow> =
        withContext(Dispatchers.Default) {
            val items =
                transferQueries.selectRunningBalanceByAccountPaginatedBackward(
                    accountId.id,
                    firstTimestamp.toEpochMilliseconds(),
                    firstId.id.toString(),
                    (pageSize + 1).toLong(),
                ) { id, timestamp, description, accountId_, currencyId, transactionAmount, runningBalance,
                    currency_id, currency_code, currency_name, currency_scaleFactor, sourceAccountId, targetAccountId,
                    ->
                    AccountRowMapper.mapRaw(
                        id,
                        timestamp,
                        description,
                        accountId_,
                        currencyId,
                        transactionAmount,
                        runningBalance,
                        currency_id,
                        currency_code,
                        currency_name,
                        currency_scaleFactor,
                        sourceAccountId,
                        targetAccountId,
                    )
                }
                    .executeAsList()
                    // Reverse to get correct display order (newest first)
                    .reversed()

            val hasMore = items.size > pageSize
            val pageItems = if (hasMore) items.drop(1) else items

            val nextPagingInfo =
                if (hasMore && pageItems.isNotEmpty()) {
                    val firstItem = pageItems.first()
                    PagingInfo(
                        lastTimestamp = firstItem.timestamp,
                        lastId = firstItem.transactionId,
                        hasMore = true,
                    )
                } else {
                    PagingInfo(
                        lastTimestamp = null,
                        lastId = null,
                        hasMore = false,
                    )
                }

            PagingResult(
                items = pageItems,
                pagingInfo = nextPagingInfo,
            )
        }

    override suspend fun getPageContainingTransaction(
        accountId: AccountId,
        transactionId: TransferId,
        pageSize: Int,
    ): PageWithTargetIndex<AccountRow> =
        withContext(Dispatchers.Default) {
            // First, get the transaction to find its timestamp
            val transaction =
                transferQueries.selectById(transactionId.id.toString(), TransferMapper::mapRaw)
                    .executeAsOneOrNull()
                    ?: throw IllegalArgumentException("Transaction not found: $transactionId")

            // Get the row position of the transaction (0-indexed, sorted by timestamp DESC)
            val rowPosition =
                transferQueries.getTransactionRowPosition(
                    accountId = accountId.id,
                    targetTimestamp = transaction.timestamp.toEpochMilliseconds(),
                    targetId = transactionId.id.toString(),
                ).executeAsOne()

            // Get total count to know if there are more items
            val totalCount =
                transferQueries.countTransactionsByAccount(accountId.id)
                    .executeAsOne()

            // Calculate offset to center the transaction in the page
            // Try to put the transaction in the middle
            val halfPage = pageSize / 2
            val idealOffset = (rowPosition - halfPage).coerceAtLeast(0)

            // Adjust offset if we're near the end to fill the page
            val maxOffset = (totalCount - pageSize).coerceAtLeast(0)
            val offset = idealOffset.coerceAtMost(maxOffset)

            // Load the page
            val items =
                transferQueries.selectRunningBalanceByAccountOffset(
                    accountId = accountId.id,
                    limit = pageSize.toLong(),
                    offset = offset,
                ) { id, timestamp, description, accountId_, currencyId, transactionAmount, runningBalance,
                    currency_id, currency_code, currency_name, currency_scaleFactor, sourceAccountId, targetAccountId,
                    ->
                    AccountRowMapper.mapRaw(
                        id,
                        timestamp,
                        description,
                        accountId_,
                        currencyId,
                        transactionAmount,
                        runningBalance,
                        currency_id,
                        currency_code,
                        currency_name,
                        currency_scaleFactor,
                        sourceAccountId,
                        targetAccountId,
                    )
                }
                    .executeAsList()

            // Find the target transaction's index within the loaded page
            val targetIndex =
                items.indexOfFirst { it.transactionId.id == transactionId.id }

            // Calculate if there are more items after this page
            val hasMore = (offset + items.size) < totalCount

            val pagingInfo =
                if (hasMore && items.isNotEmpty()) {
                    val lastItem = items.last()
                    PagingInfo(
                        lastTimestamp = lastItem.timestamp,
                        lastId = lastItem.transactionId,
                        hasMore = true,
                    )
                } else {
                    PagingInfo(
                        lastTimestamp = null,
                        lastId = null,
                        hasMore = false,
                    )
                }

            PageWithTargetIndex(
                items = items,
                targetIndex = targetIndex,
                pagingInfo = pagingInfo,
                hasPrevious = offset > 0,
            )
        }

    override suspend fun createTransfer(transfer: Transfer): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.transaction {
                transactionIdQueries.insert(transfer.id.toString())
                transferQueries.insert(
                    id = transfer.id.toString(),
                    revisionId = transfer.revisionId,
                    timestamp = transfer.timestamp.toEpochMilliseconds(),
                    description = transfer.description,
                    sourceAccountId = transfer.sourceAccountId.id,
                    targetAccountId = transfer.targetAccountId.id,
                    currencyId = transfer.amount.currency.id.toString(),
                    amount = transfer.amount.amount,
                )
            }
        }

    override suspend fun createTransfersBatch(transfers: List<Transfer>): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.transaction {
                transfers.forEach { transfer ->
                    transactionIdQueries.insert(transfer.id.toString())
                    transferQueries.insert(
                        id = transfer.id.toString(),
                        revisionId = transfer.revisionId,
                        timestamp = transfer.timestamp.toEpochMilliseconds(),
                        description = transfer.description,
                        sourceAccountId = transfer.sourceAccountId.id,
                        targetAccountId = transfer.targetAccountId.id,
                        currencyId = transfer.amount.currency.id.toString(),
                        amount = transfer.amount.amount,
                    )
                }
            }
        }

    override suspend fun updateTransfer(transfer: Transfer): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.update(
                timestamp = transfer.timestamp.toEpochMilliseconds(),
                description = transfer.description,
                sourceAccountId = transfer.sourceAccountId.id,
                targetAccountId = transfer.targetAccountId.id,
                currencyId = transfer.amount.currency.id.toString(),
                amount = transfer.amount.amount,
                id = transfer.id.toString(),
            )
        }

    override suspend fun bumpRevisionOnly(id: TransferId): Long =
        withContext(Dispatchers.Default) {
            transferQueries.transactionWithResult {
                transferQueries.bumpRevisionOnly(id.id.toString())
                transferQueries.selectById(id.id.toString()).executeAsOne().revisionId
            }
        }

    override suspend fun deleteTransaction(id: Uuid): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.delete(id.toString())
        }
}
