@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.TransactionWithRunningBalance
import com.moneymanager.domain.model.Transfer
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface TransactionRepository {
    fun getAllTransactions(): Flow<List<Transfer>>

    fun getTransactionById(id: Long): Flow<Transfer?>

    fun getTransactionsByAccount(accountId: Long): Flow<List<Transfer>>

    fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>>

    fun getTransactionsByAccountAndDateRange(
        accountId: Long,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>>

    fun getAccountBalances(): Flow<List<AccountBalance>>

    fun getRunningBalanceByAccount(accountId: Long): Flow<List<TransactionWithRunningBalance>>

    suspend fun createTransfer(
        timestamp: Instant,
        description: String,
        sourceAccountId: Long,
        targetAccountId: Long,
        assetId: Long,
        amount: Double,
    ): Long

    suspend fun updateTransfer(
        id: Long,
        timestamp: Instant,
        description: String,
        sourceAccountId: Long,
        targetAccountId: Long,
        assetId: Long,
        amount: Double,
    )

    suspend fun deleteTransaction(id: Long)
}
