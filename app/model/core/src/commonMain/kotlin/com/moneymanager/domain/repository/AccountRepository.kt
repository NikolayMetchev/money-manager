package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Transfer
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun getAllAccounts(): Flow<List<Account>>

    fun getAccountById(id: AccountId): Flow<Account?>

    suspend fun createAccount(account: Account): AccountId

    suspend fun createAccountsBatch(accounts: List<Account>): List<AccountId>

    /**
     * Updates the account and returns the new revision ID.
     */
    suspend fun updateAccount(account: Account): Long

    suspend fun deleteAccount(id: AccountId)

    suspend fun countTransfersByAccount(accountId: AccountId): Long

    suspend fun getTransfersBetweenAccounts(
        accountA: AccountId,
        accountB: AccountId,
    ): List<Transfer>

    suspend fun deleteAccountAndMoveTransactions(
        accountToDelete: AccountId,
        targetAccount: AccountId,
    )
}
