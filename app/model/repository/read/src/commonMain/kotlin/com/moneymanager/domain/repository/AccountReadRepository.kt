package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountMerge
import com.moneymanager.domain.model.AccountMergeContext
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.MergeMovedTransfer
import com.moneymanager.domain.model.Transfer
import kotlinx.coroutines.flow.Flow

interface AccountReadRepository {
    fun getAllAccounts(): Flow<List<Account>>

    fun getAccountById(id: AccountId): Flow<Account?>

    /**
     * Former names of still-existing accounts (from audit history), keyed by lowercased name, mapping
     * to the account that most recently bore that name. Lets an import resolve a renamed account when a
     * row still carries its old name, without persisting an exact-match account mapping.
     */
    suspend fun getPreviousAccountNames(): Map<String, AccountId>

    suspend fun countTransfersByAccount(accountId: AccountId): Long

    /**
     * Which of [accountIds] appear on either side of any transfer — for callers checking many
     * accounts for emptiness that would otherwise issue one [countTransfersByAccount] per account,
     * e.g. the re-import empty-account cleanup.
     */
    suspend fun accountsWithTransfers(accountIds: Collection<AccountId>): Set<AccountId>

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

    /** The transfers a given merge moved onto the survivor, so re-import can trace them to source rows. */
    suspend fun getMergeMovedTransfers(mergeId: MergeId): List<MergeMovedTransfer>
}
