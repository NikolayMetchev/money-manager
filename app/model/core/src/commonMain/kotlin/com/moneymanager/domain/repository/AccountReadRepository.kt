package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountMerge
import com.moneymanager.domain.model.AccountMergeContext
import com.moneymanager.domain.model.Transfer
import kotlinx.coroutines.flow.Flow

interface AccountReadRepository {
    fun getAllAccounts(): Flow<List<Account>>

    fun getAccountById(id: AccountId): Flow<Account?>

    suspend fun countTransfersByAccount(accountId: AccountId): Long

    suspend fun getTransfersBetweenAccounts(
        accountA: AccountId,
        accountB: AccountId,
    ): List<Transfer>

    /** Reversible (not yet undone) merges, most recent first, for surfacing an "undo merge" action. */
    fun getReversibleMerges(): Flow<List<AccountMerge>>

    /** All merges (reversed or not) in which [accountId] is the surviving account, to show in its audit. */
    fun getMergesForSurvivingAccount(accountId: AccountId): Flow<List<AccountMerge>>

    /** Merges in which [accountId] was the merged-away account, to label its audit trail. */
    suspend fun getMergesForDeletedAccount(accountId: AccountId): List<AccountMergeContext>
}
