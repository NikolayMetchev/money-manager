package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountMerge
import com.moneymanager.domain.model.AccountMergeContext
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun getAllAccounts(): Flow<List<Account>>

    fun getAccountById(id: AccountId): Flow<Account?>

    suspend fun createAccount(
        account: Account,
        source: Source,
    ): AccountId

    /** Creates accounts in one transaction, recording each account's source via [sourceFor]. */
    suspend fun createAccountsBatch(
        accounts: List<Account>,
        sourceFor: (Account) -> Source,
    ): List<AccountId>

    /**
     * Updates the account and returns the new revision ID.
     */
    suspend fun updateAccount(
        account: Account,
        source: Source,
    ): Long

    /**
     * Atomically updates account fields and/or attributes, producing a single revision bump.
     * Pass [account] = null to skip updating account fields (attribute-only update).
     * Returns the final revision ID after all changes.
     */
    suspend fun updateAccountWithAttributes(
        account: Account?,
        accountId: AccountId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        source: Source,
    ): Long

    suspend fun deleteAccount(id: AccountId)

    suspend fun countTransfersByAccount(accountId: AccountId): Long

    suspend fun getTransfersBetweenAccounts(
        accountA: AccountId,
        accountB: AccountId,
    ): List<Transfer>

    /**
     * Merges [deletedAccount] into [survivingAccount]: reassigns all of the deleted account's
     * transactions to the surviving account, then deletes the merged-away account. Records a
     * reversible [AccountMerge] so the operation can later be undone via [unmergeAccount].
     *
     * Throws if any transfers exist directly between the two accounts (they would become invalid
     * self-transfers); callers should surface this to the user before invoking.
     *
     * The deleted account's DELETE audit entry is sourced as a merge on the current device.
     *
     * @return the id of the recorded merge.
     */
    suspend fun mergeAccounts(
        deletedAccount: AccountId,
        survivingAccount: AccountId,
    ): MergeId

    /** Reversible (not yet undone) merges, most recent first, for surfacing an "undo merge" action. */
    fun getReversibleMerges(): Flow<List<AccountMerge>>

    /** All merges (reversed or not) in which [accountId] is the surviving account, to show in its audit. */
    fun getMergesForSurvivingAccount(accountId: AccountId): Flow<List<AccountMerge>>

    /** Merges in which [accountId] was the merged-away account, to label its audit trail. */
    suspend fun getMergesForDeletedAccount(accountId: AccountId): List<AccountMergeContext>

    /**
     * Reverses a previous [mergeAccounts]: recreates the deleted account (with its original id,
     * attributes and owners) and moves the reassigned transactions back. No-op if already reversed.
     * The recreated account's audit entry is sourced as a merge-undo on the current device.
     */
    suspend fun unmergeAccount(
        mergeId: MergeId,
    )
}
