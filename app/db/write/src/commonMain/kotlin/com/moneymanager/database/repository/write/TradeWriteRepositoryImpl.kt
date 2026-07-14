@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.write.TradeCreateResult
import com.moneymanager.domain.repository.write.TradeWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class TradeWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: TradeReadRepository,
) : TradeWriteRepository,
    TradeReadRepository by reader {
    private val writeQueries = database.tradeWriteQueries
    private val selectQueries = database.tradeSelectQueries
    private val transactionIdWriteQueries = database.transactionIdWriteQueries

    override suspend fun createTrade(
        timestamp: Instant,
        description: String,
        fromAccountId: AccountId,
        fromAmount: Money,
        toAccountId: AccountId,
        toAmount: Money,
        source: Source,
    ): TradeCreateResult {
        // A trade is a cross-asset exchange; a same-asset movement is a transfer. The DB CHECK only
        // blocks the same-account+same-asset degenerate case, so enforce the cross-asset rule here.
        require(fromAmount.asset.id != toAmount.asset.id) {
            "A trade must exchange two different assets (from and to assets are both ${fromAmount.asset.code})"
        }
        return withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                // Idempotency: re-importing the same conversion must not double-book. If an identical
                // trade already exists, return it instead of inserting a duplicate.
                val existing =
                    selectQueries
                        .selectMatchingTradeId(
                            timestamp = timestamp.toEpochMilliseconds(),
                            description = description,
                            from_account_id = fromAccountId.id,
                            from_asset_id = fromAmount.asset.id.id,
                            from_amount = fromAmount.amount.toString(),
                            to_account_id = toAccountId.id,
                            to_asset_id = toAmount.asset.id.id,
                            to_amount = toAmount.amount.toString(),
                        ).executeAsOneOrNull()
                if (existing != null) {
                    return@transactionWithResult TradeCreateResult(TradeId(existing), created = false)
                }
                // Allocate a transaction id (insert + last_insert_rowid must share a connection).
                transactionIdWriteQueries.insert()
                val id = transactionIdWriteQueries.lastInsertedId().executeAsOne()
                writeQueries.insert(
                    id = id,
                    revision_id = 1L,
                    timestamp = timestamp.toEpochMilliseconds(),
                    description = description,
                    from_account_id = fromAccountId.id,
                    from_asset_id = fromAmount.asset.id.id,
                    from_amount = fromAmount.amount.toString(),
                    to_account_id = toAccountId.id,
                    to_asset_id = toAmount.asset.id.id,
                    to_amount = toAmount.amount.toString(),
                )
                database.recordSource(deviceId, EntityType.TRADE, id, 1L, source)
                TradeCreateResult(TradeId(id), created = true)
            }
        }
    }

    override suspend fun deleteTrade(id: TradeId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.delete(id.id)
        }
}
