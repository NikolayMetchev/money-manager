package com.moneymanager.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.di.AppScope
import com.moneymanager.domain.model.Transaction
import com.moneymanager.domain.model.TransactionType
import com.moneymanager.domain.repository.TransactionRepository
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TransactionRepositoryImpl(
    private val database: MoneyManagerDatabase
) : TransactionRepository {

    private val queries = database.transactionQueries

    override fun getAllTransactions(): Flow<List<Transaction>> =
        queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { transactions -> transactions.map { it.toDomainModel() } }

    override fun getTransactionById(id: Long): Flow<Transaction?> =
        queries.selectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.toDomainModel() }

    override fun getTransactionsByAccount(accountId: Long): Flow<List<Transaction>> =
        queries.selectByAccount(accountId, accountId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { transactions -> transactions.map { it.toDomainModel() } }

    override fun getTransactionsByCategory(categoryId: Long): Flow<List<Transaction>> =
        queries.selectByCategory(categoryId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { transactions -> transactions.map { it.toDomainModel() } }

    override fun getTransactionsByDateRange(startDate: Instant, endDate: Instant): Flow<List<Transaction>> =
        queries.selectByDateRange(
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds()
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { transactions -> transactions.map { it.toDomainModel() } }

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
            .map { transactions -> transactions.map { it.toDomainModel() } }

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

    override suspend fun updateTransaction(transaction: Transaction) = withContext(Dispatchers.Default) {
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
    }

    override suspend fun deleteTransaction(id: Long) = withContext(Dispatchers.Default) {
        queries.delete(id)
    }

    private fun com.moneymanager.database.TransactionRecord.toDomainModel() = Transaction(
        id = id,
        accountId = accountId,
        categoryId = categoryId,
        type = TransactionType.valueOf(type),
        amount = amount,
        currency = currency,
        description = description,
        note = note,
        transactionDate = Instant.fromEpochMilliseconds(transactionDate),
        toAccountId = toAccountId,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt)
    )
}
