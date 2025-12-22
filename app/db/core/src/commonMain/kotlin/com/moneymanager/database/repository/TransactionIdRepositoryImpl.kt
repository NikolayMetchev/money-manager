@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransactionIdRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of TransactionIdRepository using SQLDelight.
 * Manages permanent transaction IDs that persist even after transfer deletion.
 */
class TransactionIdRepositoryImpl(
    database: MoneyManagerDatabase,
) : TransactionIdRepository {
    private val queries = database.transactionIdQueries

    override suspend fun create(id: TransferId): Unit =
        withContext(Dispatchers.Default) {
            queries.insert(id.toString())
        }

    override suspend fun exists(id: TransferId): Boolean =
        withContext(Dispatchers.Default) {
            queries.exists(id.toString()).executeAsOne() > 0L
        }
}
