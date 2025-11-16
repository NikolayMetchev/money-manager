package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Account
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun getAllAccounts(): Flow<List<Account>>
    fun getAccountById(id: Long): Flow<Account?>
    fun getActiveAccounts(): Flow<List<Account>>
    suspend fun createAccount(account: Account): Long
    suspend fun updateAccount(account: Account)
    suspend fun updateAccountBalance(accountId: Long, newBalance: Double)
    suspend fun deleteAccount(id: Long)
}
