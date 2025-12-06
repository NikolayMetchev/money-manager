@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.AccountMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.repository.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AccountRepositoryImpl(
    database: MoneyManagerDatabase,
) : AccountRepository {
    private val queries = database.accountQueries

    override fun getAllAccounts(): Flow<List<Account>> =
        queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(AccountMapper::mapList)

    override fun getAccountById(id: AccountId): Flow<Account?> =
        queries.selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(AccountMapper::map) }

    override suspend fun createAccount(account: Account): AccountId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insert(
                        name = account.name,
                        openingDate = account.openingDate.toEpochMilliseconds(),
                    )
                    queries.lastInsertRowId().executeAsOne()
                }
            AccountId(id)
        }

    override suspend fun createAccountsBatch(accounts: List<Account>): List<AccountId> =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                accounts.map { account ->
                    queries.insert(
                        name = account.name,
                        openingDate = account.openingDate.toEpochMilliseconds(),
                    )
                    val id = queries.lastInsertRowId().executeAsOne()
                    AccountId(id)
                }
            }
        }

    override suspend fun updateAccount(account: Account): Unit =
        withContext(Dispatchers.Default) {
            queries.update(
                name = account.name,
                id = account.id.id,
            )
        }

    override suspend fun deleteAccount(id: AccountId): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id.id)
        }
}
