@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.TransactionWithRunningBalance
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.time.Instant.Companion.fromEpochMilliseconds
import kotlin.uuid.Uuid

class TransactionRepositoryImpl(
    private val database: MoneyManagerDatabase,
) : TransactionRepository {
    private val transferQueries = database.transferQueries

    override fun getAllTransactions(): Flow<List<Transfer>> =
        transferQueries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    Transfer(
                        id = Uuid.parse(row.id),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = row.sourceAccountId,
                        targetAccountId = row.targetAccountId,
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        amount = row.amount,
                    )
                }
            }

    override fun getTransactionById(id: Uuid): Flow<Transfer?> =
        transferQueries.selectById(id.toString())
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row ->
                row?.let {
                    Transfer(
                        id = Uuid.parse(it.id),
                        timestamp = fromEpochMilliseconds(it.timestamp),
                        description = it.description,
                        sourceAccountId = it.sourceAccountId,
                        targetAccountId = it.targetAccountId,
                        currencyId = CurrencyId(Uuid.parse(it.currencyId)),
                        amount = it.amount,
                    )
                }
            }

    override fun getTransactionsByAccount(accountId: Long): Flow<List<Transfer>> =
        transferQueries.selectByAccount(accountId, accountId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    Transfer(
                        id = Uuid.parse(row.id),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = row.sourceAccountId,
                        targetAccountId = row.targetAccountId,
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        amount = row.amount,
                    )
                }
            }

    override fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries.selectByDateRange(
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds(),
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    Transfer(
                        id = Uuid.parse(row.id),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = row.sourceAccountId,
                        targetAccountId = row.targetAccountId,
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        amount = row.amount,
                    )
                }
            }

    override fun getTransactionsByAccountAndDateRange(
        accountId: Long,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries.selectByAccountAndDateRange(
            accountId,
            accountId,
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds(),
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    Transfer(
                        id = Uuid.parse(row.id),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = row.sourceAccountId,
                        targetAccountId = row.targetAccountId,
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        amount = row.amount,
                    )
                }
            }

    override fun getAccountBalances(): Flow<List<AccountBalance>> =
        transferQueries.selectAllBalances()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    AccountBalance(
                        accountId = row.accountId,
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        balance = row.balance ?: 0.0,
                    )
                }
            }

    override fun getRunningBalanceByAccount(accountId: Long): Flow<List<TransactionWithRunningBalance>> =
        transferQueries.selectRunningBalanceByAccount(accountId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    TransactionWithRunningBalance(
                        transactionId = Uuid.parse(row.id),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        accountId = row.accountId,
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        transactionAmount = row.transactionAmount,
                        runningBalance = row.runningBalance ?: 0.0,
                    )
                }
            }

    override suspend fun createTransfer(transfer: Transfer): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.insert(
                id = transfer.id.toString(),
                timestamp = transfer.timestamp.toEpochMilliseconds(),
                description = transfer.description,
                sourceAccountId = transfer.sourceAccountId,
                targetAccountId = transfer.targetAccountId,
                currencyId = transfer.currencyId.uuid.toString(),
                amount = transfer.amount,
            )
            Unit
        }

    override suspend fun updateTransfer(transfer: Transfer): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.update(
                timestamp = transfer.timestamp.toEpochMilliseconds(),
                description = transfer.description,
                sourceAccountId = transfer.sourceAccountId,
                targetAccountId = transfer.targetAccountId,
                currencyId = transfer.currencyId.uuid.toString(),
                amount = transfer.amount,
                id = transfer.id.toString(),
            )
            Unit
        }

    override suspend fun deleteTransaction(id: Uuid): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.delete(id.toString())
            Unit
        }
}
