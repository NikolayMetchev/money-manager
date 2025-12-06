@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Transfer
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface TransactionRepository {
    fun getAllTransactions(): Flow<List<Transfer>>

    fun getTransactionById(id: Uuid): Flow<Transfer?>

    fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>>

    fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>>

    fun getTransactionsByAccountAndDateRange(
        accountId: AccountId,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>>

    fun getAccountBalances(): Flow<List<AccountBalance>>

    fun getRunningBalanceByAccount(accountId: AccountId): Flow<List<AccountRow>>

    suspend fun createTransfer(transfer: Transfer)

    suspend fun createTransfersBatch(transfers: List<Transfer>)

    suspend fun updateTransfer(transfer: Transfer)

    suspend fun deleteTransaction(id: Uuid)
}
