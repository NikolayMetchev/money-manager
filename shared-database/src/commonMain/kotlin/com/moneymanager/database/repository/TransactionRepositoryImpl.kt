@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.TransactionMapper
import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.domain.model.Transaction
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class TransactionRepositoryImpl(
    private val database: MoneyManagerDatabase
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

    override fun getTransactionsByCategory(categoryId: Long): Flow<List<Transaction>> =
        queries.selectByCategory(categoryId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(TransactionMapper::mapList)

    override fun getTransactionsByDateRange(startDate: Instant, endDate: Instant): Flow<List<Transaction>> =
        queries.selectByDateRange(
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds()
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(TransactionMapper::mapList)

    override fun getTransactionsByAccountAndDateRange(
        accountId: Long,
        startDate: Instant,
        endDate: Instant
    ): Flow<List<Transaction>> =
        queries.selectByAccountAndDateRange(
            accountId,
            accountId,
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds()
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(TransactionMapper::mapList)

    override suspend fun createTransaction(transaction: Transaction): Long = withContext(Dispatchers.Default) {
        queries.insert(
            accountId = transaction.accountId,
            categoryId = transaction.categoryId,
            type = transaction.type.name,
            amount = transaction.amount,
            currency = transaction.currency,
            description = transaction.description,
            note = transaction.note,
            transactionDate = transaction.transactionDate.toEpochMilliseconds(),
            toAccountId = transaction.toAccountId,
            createdAt = transaction.createdAt.toEpochMilliseconds(),
            updatedAt = transaction.updatedAt.toEpochMilliseconds()
        )
        queries.lastInsertRowId().executeAsOne()
    }

    override suspend fun updateTransaction(transaction: Transaction): Unit = withContext(Dispatchers.Default) {
        queries.update(
            accountId = transaction.accountId,
            categoryId = transaction.categoryId,
            type = transaction.type.name,
            amount = transaction.amount,
            currency = transaction.currency,
            description = transaction.description,
            note = transaction.note,
            transactionDate = transaction.transactionDate.toEpochMilliseconds(),
            toAccountId = transaction.toAccountId,
            updatedAt = transaction.updatedAt.toEpochMilliseconds(),
            id = transaction.id
        )
        Unit
    }

    override suspend fun deleteTransaction(id: Long): Unit = withContext(Dispatchers.Default) {
        queries.delete(id)
        Unit
    }
}
