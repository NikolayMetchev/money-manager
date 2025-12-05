@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.TransactionWithRunningBalance
import com.moneymanager.domain.model.Transfer
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface TransactionRepository {
    fun getAllTransactions(): Flow<List<Transfer>>

    fun getTransactionById(id: Uuid): Flow<Transfer?>

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

    suspend fun createTransfer(transfer: Transfer)

    suspend fun updateTransfer(transfer: Transfer)

    suspend fun deleteTransaction(id: Uuid)
}
