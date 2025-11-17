package com.moneymanager.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.data.mapper.AccountMapper
import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.di.AppScope
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.repository.AccountRepository
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AccountRepositoryImpl(
    private val database: MoneyManagerDatabase
) : AccountRepository {

    private val queries = database.accountQueries

    override fun getAllAccounts(): Flow<List<Account>> =
        queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { accounts -> AccountMapper.mapList(accounts) }

    override fun getAccountById(id: Long): Flow<Account?> =
        queries.selectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let { AccountMapper.map(it) } }

    override fun getActiveAccounts(): Flow<List<Account>> =
        queries.selectActive()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { accounts -> AccountMapper.mapList(accounts) }

    override suspend fun createAccount(account: Account): Long = withContext(Dispatchers.Default) {
        queries.insert(
            name = account.name,
            type = account.type.name,
            currency = account.currency,
            initialBalance = account.initialBalance,
            currentBalance = account.currentBalance,
            color = account.color,
            icon = account.icon,
            isActive = if (account.isActive) 1 else 0,
            createdAt = account.createdAt.toEpochMilliseconds(),
            updatedAt = account.updatedAt.toEpochMilliseconds()
        )
        queries.lastInsertRowId().executeAsOne()
    }

    override suspend fun updateAccount(account: Account): Unit = withContext(Dispatchers.Default) {
        queries.update(
            name = account.name,
            type = account.type.name,
            currency = account.currency,
            color = account.color,
            icon = account.icon,
            isActive = if (account.isActive) 1 else 0,
            updatedAt = account.updatedAt.toEpochMilliseconds(),
            id = account.id
        )
        Unit
    }

    override suspend fun updateAccountBalance(accountId: Long, newBalance: Double): Unit = withContext(Dispatchers.Default) {
        queries.updateBalance(
            currentBalance = newBalance,
            updatedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
            id = accountId
        )
        Unit
    }

    override suspend fun deleteAccount(id: Long): Unit = withContext(Dispatchers.Default) {
        queries.delete(id)
        Unit
    }
}
