@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Transaction
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface TransactionRepository {
    fun getAllTransactions(): Flow<List<Transaction>>

    fun getTransactionById(id: Long): Flow<Transaction?>

    fun getTransactionsByAccount(accountId: Long): Flow<List<Transaction>>

    fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transaction>>

    fun getTransactionsByAccountAndDateRange(
        accountId: Long,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transaction>>

    suspend fun createTransaction(transaction: Transaction): Long

    suspend fun updateTransaction(transaction: Transaction)

    suspend fun deleteTransaction(id: Long)
}
