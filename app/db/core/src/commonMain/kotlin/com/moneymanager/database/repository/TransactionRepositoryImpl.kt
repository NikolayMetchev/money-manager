@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.TransactionWithRunningBalance
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.time.Instant.Companion.fromEpochMilliseconds

class TransactionRepositoryImpl(
    private val database: MoneyManagerDatabase,
) : TransactionRepository {
    private val transactionQueries = database.transactionQueries
    private val transferQueries = database.transferQueries

    override fun getAllTransactions(): Flow<List<Transfer>> =
        transferQueries.selectAllWithTransaction()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    Transfer(
                        id = row.transactionId,
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = row.sourceAccountId,
                        targetAccountId = row.targetAccountId,
                        assetId = row.assetId,
                        amount = row.amount,
                    )
                }
            }

    override fun getTransactionById(id: Long): Flow<Transfer?> =
        transferQueries.selectByIdWithTransaction(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row ->
                row?.let {
                    Transfer(
                        id = it.transactionId,
                        timestamp = fromEpochMilliseconds(it.timestamp),
                        description = it.description,
                        sourceAccountId = it.sourceAccountId,
                        targetAccountId = it.targetAccountId,
                        assetId = it.assetId,
                        amount = it.amount,
                    )
                }
            }

    override fun getTransactionsByAccount(accountId: Long): Flow<List<Transfer>> =
        transferQueries.selectByAccountWithTransaction(accountId, accountId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    Transfer(
                        id = row.transactionId,
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = row.sourceAccountId,
                        targetAccountId = row.targetAccountId,
                        assetId = row.assetId,
                        amount = row.amount,
                    )
                }
            }

    override fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        getAllTransactions().map { transactions ->
            transactions.filter { it.timestamp in startDate..endDate }
        }

    override fun getTransactionsByAccountAndDateRange(
        accountId: Long,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries.selectByAccountAndDateRangeWithTransaction(
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
                        id = row.transactionId,
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = row.sourceAccountId,
                        targetAccountId = row.targetAccountId,
                        assetId = row.assetId,
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
                        assetId = row.assetId,
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
                        transactionId = row.transactionId,
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        accountId = row.accountId,
                        assetId = row.assetId,
                        transactionAmount = row.transactionAmount,
                        runningBalance = row.runningBalance ?: 0.0,
                    )
                }
            }

    override suspend fun createTransfer(
        timestamp: Instant,
        description: String,
        sourceAccountId: Long,
        targetAccountId: Long,
        assetId: Long,
        amount: Double,
    ): Long =
        withContext(Dispatchers.Default) {
            transactionQueries.transactionWithResult {
                // Insert transaction first
                transactionQueries.insert(
                    timestamp = timestamp.toEpochMilliseconds(),
                    description = description,
                )
                val transactionId = transactionQueries.lastInsertRowId().executeAsOne()

                // Then insert transfer with the transaction ID
                transferQueries.insert(
                    transactionId = transactionId,
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    assetId = assetId,
                    amount = amount,
                )

                transactionId
            }
        }

    override suspend fun updateTransfer(
        id: Long,
        timestamp: Instant,
        description: String,
        sourceAccountId: Long,
        targetAccountId: Long,
        assetId: Long,
        amount: Double,
    ): Unit =
        withContext(Dispatchers.Default) {
            transactionQueries.transaction {
                // Update transaction
                transactionQueries.update(
                    timestamp = timestamp.toEpochMilliseconds(),
                    description = description,
                    id = id,
                )

                // Update transfer
                transferQueries.update(
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    assetId = assetId,
                    amount = amount,
                    transactionId = id,
                )
            }
            Unit
        }

    override suspend fun deleteTransaction(id: Long): Unit =
        withContext(Dispatchers.Default) {
            // Due to CASCADE, deleting the transaction will also delete the transfer
            transactionQueries.delete(id)
            Unit
        }
}
