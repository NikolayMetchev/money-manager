package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.repository.ReportedBalanceReadRepository
import com.moneymanager.domain.repository.write.ReportedBalanceWrite
import com.moneymanager.domain.repository.write.ReportedBalanceWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Balances are written this many per database transaction. */
private const val BALANCE_CHUNK_ROWS = 500

class ReportedBalanceWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: ReportedBalanceReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ReportedBalanceWriteRepository,
    ReportedBalanceReadRepository by reader {
    private val queries = database.reportedBalanceWriteQueries

    override suspend fun writeBalances(balances: List<ReportedBalanceWrite>) {
        if (balances.isEmpty()) return
        withContext(coroutineContext) {
            balances.chunked(BALANCE_CHUNK_ROWS).forEach { chunk ->
                queries.transaction { chunk.forEach { write(it) } }
            }
        }
    }

    private suspend fun write(balance: ReportedBalanceWrite) {
        val recordId = balance.recordId.id
        val accountId = balance.accountId.id
        val assetId = balance.amount.asset.id.id
        val timestamp = balance.timestamp.toEpochMilliseconds()
        val amount = balance.amount.amount.toString()
        val existing = database.reportedBalanceSelectQueries.selectByRecord(recordId).awaitAsOneOrNull()
        when {
            existing == null -> {
                queries.insertBalance(recordId, accountId, assetId, timestamp, amount)
                val id = queries.lastInsertedId().awaitAsOne()
                database.recordSource(deviceId, EntityType.REPORTED_BALANCE, id, 1, balance.source)
            }

            existing.account_id != accountId || existing.asset_id != assetId || existing.timestamp != timestamp ||
                existing.amount != amount -> {
                val revision = existing.revision_id + 1
                queries.updateBalance(accountId, assetId, timestamp, amount, revision, existing.id)
                queries.unreserve(existing.id)
                database.recordSource(deviceId, EntityType.REPORTED_BALANCE, existing.id, revision, balance.source)
            }

            else -> {
                queries.unreserve(existing.id)
            }
        }
    }

    override suspend fun deleteReserved() {
        queries.deleteReserved()
    }
}
