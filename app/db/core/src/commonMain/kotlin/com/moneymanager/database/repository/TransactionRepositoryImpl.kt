@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.time.Instant.Companion.fromEpochMilliseconds
import kotlin.uuid.Uuid

class TransactionRepositoryImpl(
    database: MoneyManagerDatabase,
) : TransactionRepository {
    private val transferQueries = database.transferQueries

    override fun getAllTransactions(): Flow<List<Transfer>> =
        transferQueries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    Transfer(
                        id = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = AccountId(row.sourceAccountId),
                        targetAccountId = AccountId(row.targetAccountId),
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        amount = row.amount,
                    )
                }
            }

    override fun getTransactionById(id: Uuid): Flow<Transfer?> =
        transferQueries.selectById(id.toString())
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row ->
                row?.let {
                    Transfer(
                        id = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(it.timestamp),
                        description = it.description,
                        sourceAccountId = AccountId(it.sourceAccountId),
                        targetAccountId = AccountId(it.targetAccountId),
                        currencyId = CurrencyId(Uuid.parse(it.currencyId)),
                        amount = it.amount,
                    )
                }
            }

    override fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>> =
        transferQueries.selectByAccount(accountId.id, accountId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    Transfer(
                        id = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = AccountId(row.sourceAccountId),
                        targetAccountId = AccountId(row.targetAccountId),
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        amount = row.amount,
                    )
                }
            }

    override fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries.selectByDateRange(
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds(),
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    Transfer(
                        id = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = AccountId(row.sourceAccountId),
                        targetAccountId = AccountId(row.targetAccountId),
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        amount = row.amount,
                    )
                }
            }

    override fun getTransactionsByAccountAndDateRange(
        accountId: AccountId,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries.selectByAccountAndDateRange(
            accountId.id,
            accountId.id,
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds(),
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    Transfer(
                        id = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = AccountId(row.sourceAccountId),
                        targetAccountId = AccountId(row.targetAccountId),
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        amount = row.amount,
                    )
                }
            }

    override fun getAccountBalances(): Flow<List<AccountBalance>> =
        transferQueries.selectAllBalances()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    AccountBalance(
                        accountId = AccountId(row.accountId),
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        balance = row.balance ?: 0.0,
                    )
                }
            }

    override fun getRunningBalanceByAccount(accountId: AccountId): Flow<List<AccountRow>> =
        transferQueries.selectRunningBalanceByAccount(accountId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    AccountRow(
                        transactionId = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        accountId = AccountId(row.accountId),
                        currencyId = CurrencyId(Uuid.parse(row.currencyId)),
                        transactionAmount = row.transactionAmount,
                        runningBalance = row.runningBalance ?: 0.0,
                    )
                }
            }

    override suspend fun createTransfer(transfer: Transfer): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.insert(
                id = transfer.id.toString(),
                timestamp = transfer.timestamp.toEpochMilliseconds(),
                description = transfer.description,
                sourceAccountId = transfer.sourceAccountId.id,
                targetAccountId = transfer.targetAccountId.id,
                currencyId = transfer.currencyId.toString(),
                amount = transfer.amount,
            )
        }

    override suspend fun createTransfersBatch(transfers: List<Transfer>): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.transaction {
                transfers.forEach { transfer ->
                    transferQueries.insert(
                        id = transfer.id.toString(),
                        timestamp = transfer.timestamp.toEpochMilliseconds(),
                        description = transfer.description,
                        sourceAccountId = transfer.sourceAccountId.id,
                        targetAccountId = transfer.targetAccountId.id,
                        currencyId = transfer.currencyId.toString(),
                        amount = transfer.amount,
                    )
                }
            }
        }

    override suspend fun updateTransfer(transfer: Transfer): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.update(
                timestamp = transfer.timestamp.toEpochMilliseconds(),
                description = transfer.description,
                sourceAccountId = transfer.sourceAccountId.id,
                targetAccountId = transfer.targetAccountId.id,
                currencyId = transfer.currencyId.toString(),
                amount = transfer.amount,
                id = transfer.id.toString(),
            )
        }

    override suspend fun deleteTransaction(id: Uuid): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.delete(id.toString())
        }
}
