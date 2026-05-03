package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.DeclinedTransaction
import com.moneymanager.domain.repository.DeclinedTransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DeclinedTransactionRepositoryImpl(
    database: MoneyManagerDatabase,
) : DeclinedTransactionRepository {
    private val queries = database.declinedTransactionQueries

    override suspend fun insert(
        transactionId: Long,
        declineReason: String,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.insert(transactionId, declineReason)
        }

    override fun getAll(): Flow<List<DeclinedTransaction>> =
        queries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { DeclinedTransaction(it.transaction_id, it.decline_reason) } }

    override suspend fun getById(transactionId: Long): DeclinedTransaction? =
        withContext(Dispatchers.Default) {
            queries
                .selectById(transactionId)
                .executeAsOneOrNull()
                ?.let { DeclinedTransaction(it.transaction_id, it.decline_reason) }
        }

    override suspend fun isDeclined(transactionId: Long): Boolean =
        withContext(Dispatchers.Default) {
            queries.isDeclined(transactionId).executeAsOne() > 0
        }

    override suspend fun delete(transactionId: Long): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(transactionId)
        }
}
