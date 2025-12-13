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
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.Transfer
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

    override suspend fun createTransfer(transfer: Transfer): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.insert(
                id = transfer.id.toString(),
                timestamp = transfer.timestamp.toEpochMilliseconds(),
                description = transfer.description,
                sourceAccountId = transfer.sourceAccountId.id,
                targetAccountId = transfer.targetAccountId.id,
                currencyId = transfer.amount.currency.id.toString(),
                amount = transfer.amount.amount,
            )
        }

    override suspend fun createTransfersBatch(transfers: List<Transfer>): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.transaction {
                transfers.forEach { transfer ->
                    transferQueries.insert(
                        id = transfer.id.toString(),
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

    override suspend fun deleteTransaction(id: Uuid): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.delete(id.toString())
        }
}
