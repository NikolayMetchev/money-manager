@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
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
                    val currency =
                        Currency(
                            id = CurrencyId(Uuid.parse(row.currency_id)),
                            code = row.currency_code,
                            name = row.currency_name,
                            scaleFactor = row.currency_scaleFactor.toInt(),
                        )
                    Transfer(
                        id = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = AccountId(row.sourceAccountId),
                        targetAccountId = AccountId(row.targetAccountId),
                        amount = Money(row.amount, currency),
                    )
                }
            }

    override fun getTransactionById(id: Uuid): Flow<Transfer?> =
        transferQueries.selectById(id.toString())
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row ->
                row?.let {
                    val currency =
                        Currency(
                            id = CurrencyId(Uuid.parse(it.currency_id)),
                            code = it.currency_code,
                            name = it.currency_name,
                            scaleFactor = it.currency_scaleFactor.toInt(),
                        )
                    Transfer(
                        id = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(it.timestamp),
                        description = it.description,
                        sourceAccountId = AccountId(it.sourceAccountId),
                        targetAccountId = AccountId(it.targetAccountId),
                        amount = Money(it.amount, currency),
                    )
                }
            }

    override fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>> =
        transferQueries.selectByAccount(accountId.id, accountId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    val currency =
                        Currency(
                            id = CurrencyId(Uuid.parse(row.currency_id)),
                            code = row.currency_code,
                            name = row.currency_name,
                            scaleFactor = row.currency_scaleFactor.toInt(),
                        )
                    Transfer(
                        id = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = AccountId(row.sourceAccountId),
                        targetAccountId = AccountId(row.targetAccountId),
                        amount = Money(row.amount, currency),
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
                    val currency =
                        Currency(
                            id = CurrencyId(Uuid.parse(row.currency_id)),
                            code = row.currency_code,
                            name = row.currency_name,
                            scaleFactor = row.currency_scaleFactor.toInt(),
                        )
                    Transfer(
                        id = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = AccountId(row.sourceAccountId),
                        targetAccountId = AccountId(row.targetAccountId),
                        amount = Money(row.amount, currency),
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
                    val currency =
                        Currency(
                            id = CurrencyId(Uuid.parse(row.currency_id)),
                            code = row.currency_code,
                            name = row.currency_name,
                            scaleFactor = row.currency_scaleFactor.toInt(),
                        )
                    Transfer(
                        id = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        sourceAccountId = AccountId(row.sourceAccountId),
                        targetAccountId = AccountId(row.targetAccountId),
                        amount = Money(row.amount, currency),
                    )
                }
            }

    override fun getAccountBalances(): Flow<List<AccountBalance>> =
        transferQueries.selectAllBalances()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    val currency =
                        Currency(
                            id = CurrencyId(Uuid.parse(row.currency_id)),
                            code = row.currency_code,
                            name = row.currency_name,
                            scaleFactor = row.currency_scaleFactor.toInt(),
                        )
                    AccountBalance(
                        accountId = AccountId(row.accountId),
                        balance = Money(row.balance ?: 0, currency),
                    )
                }
            }

    override fun getRunningBalanceByAccount(accountId: AccountId): Flow<List<AccountRow>> =
        transferQueries.selectRunningBalanceByAccount(accountId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    val currency =
                        Currency(
                            id = CurrencyId(Uuid.parse(row.currency_id)),
                            code = row.currency_code,
                            name = row.currency_name,
                            scaleFactor = row.currency_scaleFactor.toInt(),
                        )
                    AccountRow(
                        transactionId = TransferId(Uuid.parse(row.id)),
                        timestamp = fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        accountId = AccountId(row.accountId),
                        transactionAmount = Money(row.transactionAmount, currency),
                        runningBalance = Money(row.runningBalance ?: 0, currency),
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
                currencyId = transfer.amount.currency.id.toString(),
                amount = transfer.amount.amount,
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
                        currencyId = transfer.amount.currency.id.toString(),
                        amount = transfer.amount.amount,
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
                currencyId = transfer.amount.currency.id.toString(),
                amount = transfer.amount.amount,
                id = transfer.id.toString(),
            )
        }

    override suspend fun deleteTransaction(id: Uuid): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.delete(id.toString())
        }
}
