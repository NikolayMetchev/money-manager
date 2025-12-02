@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.TransactionMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.Transaction
import com.moneymanager.domain.model.TransactionWithRunningBalance
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
    private val queries = database.transactionQueries

    override fun getAllTransactions(): Flow<List<Transaction>> =
        queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(TransactionMapper::mapList)

    override fun getTransactionById(id: Long): Flow<Transaction?> =
        queries.selectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(TransactionMapper::map) }

    override fun getTransactionsByAccount(accountId: Long): Flow<List<Transaction>> =
        queries.selectByAccount(accountId, accountId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(TransactionMapper::mapList)

    override fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transaction>> =
        queries.selectByDateRange(
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds(),
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(TransactionMapper::mapList)

    override fun getTransactionsByAccountAndDateRange(
        accountId: Long,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transaction>> =
        queries.selectByAccountAndDateRange(
            accountId,
            accountId,
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds(),
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(TransactionMapper::mapList)

    override fun getAccountBalances(): Flow<List<AccountBalance>> =
        getAllTransactions().map { transactions ->
            val balanceMap = mutableMapOf<Pair<Long, Long>, Double>()

            transactions.forEach { transaction ->
                // Add to target account (incoming)
                val targetKey = Pair(transaction.targetAccountId, transaction.assetId)
                balanceMap[targetKey] = balanceMap.getOrDefault(targetKey, 0.0) + transaction.amount

                // Subtract from source account (outgoing)
                val sourceKey = Pair(transaction.sourceAccountId, transaction.assetId)
                balanceMap[sourceKey] = balanceMap.getOrDefault(sourceKey, 0.0) - transaction.amount
            }

            balanceMap.map { (key, balance) ->
                AccountBalance(
                    accountId = key.first,
                    assetId = key.second,
                    balance = balance,
                )
            }
        }

    override fun getRunningBalanceByAccount(accountId: Long): Flow<List<TransactionWithRunningBalance>> =
        queries.selectRunningBalanceByAccount(accountId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    TransactionWithRunningBalance(
                        transactionId = row.transactionId,
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        accountId = row.accountId,
                        assetId = row.assetId,
                        transactionAmount = row.transactionAmount,
                        runningBalance = row.runningBalance ?: 0.0,
                    )
                }
            }

    override suspend fun createTransaction(transaction: Transaction): Long =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                queries.insert(
                    sourceAccountId = transaction.sourceAccountId,
                    targetAccountId = transaction.targetAccountId,
                    assetId = transaction.assetId,
                    amount = transaction.amount,
                    timestamp = transaction.timestamp.toEpochMilliseconds(),
                )
                queries.lastInsertRowId().executeAsOne()
            }
        }

    override suspend fun updateTransaction(transaction: Transaction): Unit =
        withContext(Dispatchers.Default) {
            queries.update(
                sourceAccountId = transaction.sourceAccountId,
                targetAccountId = transaction.targetAccountId,
                assetId = transaction.assetId,
                amount = transaction.amount,
                timestamp = transaction.timestamp.toEpochMilliseconds(),
                id = transaction.id,
            )
            Unit
        }

    override suspend fun deleteTransaction(id: Long): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id)
            Unit
        }
}
