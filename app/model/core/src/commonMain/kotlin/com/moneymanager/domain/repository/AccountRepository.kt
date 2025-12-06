package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun getAllAccounts(): Flow<List<Account>>

    fun getAccountById(id: AccountId): Flow<Account?>

    suspend fun createAccount(account: Account): AccountId

    suspend fun updateAccount(account: Account)

    suspend fun deleteAccount(id: AccountId)
}
