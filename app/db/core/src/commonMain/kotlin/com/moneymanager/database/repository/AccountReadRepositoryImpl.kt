@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.AccountMapper
import com.moneymanager.database.mapper.TransferMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountMerge
import com.moneymanager.domain.model.AccountMergeContext
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.repository.AccountReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class AccountReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : AccountReadRepository {
    private val selectQueries = database.accountSelectQueries
    private val transferSelectQueries = database.transferSelectQueries
    private val mergeSelectQueries = database.accountMergeSelectQueries

    override fun getAllAccounts(): Flow<List<Account>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(AccountMapper::mapList)

    override fun getAccountById(id: AccountId): Flow<Account?> =
        selectQueries
            .selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(AccountMapper::map) }

    override suspend fun countTransfersByAccount(accountId: AccountId): Long =
        withContext(Dispatchers.Default) {
            transferSelectQueries.countTransfersByAccount(accountId.id).executeAsOne()
        }

    override suspend fun getTransfersBetweenAccounts(
        accountA: AccountId,
        accountB: AccountId,
    ): List<Transfer> =
        withContext(Dispatchers.Default) {
            transferSelectQueries
                .selectTransfersBetweenAccounts(
                    accountA = accountA.id,
                    accountB = accountB.id,
                    TransferMapper::mapRaw,
                ).executeAsList()
        }

    override fun getReversibleMerges(): Flow<List<AccountMerge>> =
        mergeSelectQueries
            .selectReversible { id, mergedAt, survivingAccountId, deletedAccountId, deletedAccountName, transferCount ->
                AccountMerge(
                    id = MergeId(id),
                    mergedAt = Instant.fromEpochMilliseconds(mergedAt),
                    survivingAccountId = AccountId(survivingAccountId),
                    deletedAccountId = AccountId(deletedAccountId),
                    deletedAccountName = deletedAccountName,
                    transferCount = transferCount,
                )
            }.asFlow()
            .mapToList(Dispatchers.Default)

    override fun getMergesForSurvivingAccount(accountId: AccountId): Flow<List<AccountMerge>> =
        mergeSelectQueries
            .selectBySurvivingAccount(accountId.id) {
                id,
                mergedAt,
                survivingAccountId,
                deletedAccountId,
                deletedAccountName,
                reversed,
                transferCount,
                ->
                AccountMerge(
                    id = MergeId(id),
                    mergedAt = Instant.fromEpochMilliseconds(mergedAt),
                    survivingAccountId = AccountId(survivingAccountId),
                    deletedAccountId = AccountId(deletedAccountId),
                    deletedAccountName = deletedAccountName,
                    transferCount = transferCount,
                    reversed = reversed != 0L,
                )
            }.asFlow()
            .mapToList(Dispatchers.Default)

    override suspend fun getMergesForDeletedAccount(accountId: AccountId): List<AccountMergeContext> =
        withContext(Dispatchers.Default) {
            mergeSelectQueries
                .selectByDeletedAccount(accountId.id) { revisionId, survivingAccountId, reversed, survivingAccountName ->
                    AccountMergeContext(
                        deletedAccountRevisionId = revisionId,
                        survivingAccountId = AccountId(survivingAccountId),
                        survivingAccountName = survivingAccountName,
                        reversed = reversed != 0L,
                    )
                }.executeAsList()
        }
}
