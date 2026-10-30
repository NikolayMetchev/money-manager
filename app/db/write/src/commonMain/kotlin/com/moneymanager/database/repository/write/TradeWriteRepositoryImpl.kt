package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.write.NewTrade
import com.moneymanager.domain.repository.write.TradeCreateResult
import com.moneymanager.domain.repository.write.TradeWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TradeWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: TradeReadRepository,
) : TradeWriteRepository,
    TradeReadRepository by reader {
    private val writeQueries = database.tradeWriteQueries
    private val selectQueries = database.tradeSelectQueries
    private val transactionIdWriteQueries = database.transactionIdWriteQueries

    override suspend fun createTrades(trades: List<NewTrade>): List<TradeCreateResult> {
        // A trade is a cross-asset exchange; a same-asset movement is a transfer. The DB CHECK only
        // blocks the same-account+same-asset degenerate case, so enforce the cross-asset rule here.
        trades.forEach { trade ->
            require(trade.fromAmount.asset.id != trade.toAmount.asset.id) {
                "A trade must exchange two different assets (from and to assets are both ${trade.fromAmount.asset.code})"
            }
        }
        if (trades.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                trades.map { trade ->
                    createOne(trade).also { result ->
                        trade.attributes.forEach { writeQueries.upsertAttribute(result.id.id, it.typeId.id, it.value) }
                    }
                }
            }
        }
    }

    /** Creates or reuses one trade. Must be called inside an open transaction. */
    private suspend fun createOne(trade: NewTrade): TradeCreateResult {
        val timestamp = trade.timestamp.toEpochMilliseconds()
        // Multiset idempotency: re-importing the same conversion must not double-book, but N
        // genuinely-repeated identical fills must book as N rows. Reuse the occurrence-th existing
        // identical trade (0-based, ordered by id); once exhausted, fall through to insert.
        val existing =
            selectQueries
                .selectMatchingTradeId(
                    timestamp = timestamp,
                    from_account_id = trade.fromAccountId.id,
                    from_asset_id = trade.fromAmount.asset.id.id,
                    from_amount = trade.fromAmount.amount.toString(),
                    to_account_id = trade.toAccountId.id,
                    to_asset_id = trade.toAmount.asset.id.id,
                    to_amount = trade.toAmount.amount.toString(),
                    occurrence = trade.occurrence.toLong(),
                ).awaitAsOneOrNull()
        if (existing != null) return TradeCreateResult(TradeId(existing), created = false)
        // Allocate a transaction id (insert + last_insert_rowid must share a connection).
        transactionIdWriteQueries.insert()
        val id = transactionIdWriteQueries.lastInsertedId().awaitAsOne()
        writeQueries.insert(
            id = id,
            revision_id = 1L,
            timestamp = timestamp,
            description = trade.description,
            from_account_id = trade.fromAccountId.id,
            from_asset_id = trade.fromAmount.asset.id.id,
            from_amount = trade.fromAmount.amount.toString(),
            to_account_id = trade.toAccountId.id,
            to_asset_id = trade.toAmount.asset.id.id,
            to_amount = trade.toAmount.amount.toString(),
        )
        database.recordSource(deviceId, EntityType.TRADE, id, 1L, trade.source)
        return TradeCreateResult(TradeId(id), created = true)
    }

    override suspend fun deleteTrades(ids: Collection<TradeId>): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.transaction {
                ids.forEach { writeQueries.delete(it.id) }
            }
        }

    override suspend fun upsertAttributes(
        id: TradeId,
        attributes: List<NewAttribute>,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.transaction {
                attributes.forEach { writeQueries.upsertAttribute(id.id, it.typeId.id, it.value) }
            }
        }

    override suspend fun removeAttribute(
        id: TradeId,
        typeId: AttributeTypeId,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.deleteAttribute(id.id, typeId.id)
        }
}
