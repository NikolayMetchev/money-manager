package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.write.NewTrade
import com.moneymanager.domain.repository.write.TradeCreateResult
import com.moneymanager.domain.repository.write.TradeEdit
import com.moneymanager.domain.repository.write.TradeWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

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
        // A re-run takes back the trade it kept standing (#1510), writing only what changed. One that still says what the
        // record says is taken back before the identical-trade lookup below, which skips reserved trades: an identical trade
        // another source booked would otherwise take the record, and this one (with its hand edits) would be settled away.
        val reused = trade.reuseId
        val current = reused?.let { selectQueries.selectByIds(listOf(it.id)).awaitAsOneOrNull() }
        val unchanged =
            current != null &&
                current.timestamp == timestamp &&
                current.description == trade.description &&
                current.from_account_id == trade.fromAccountId.id &&
                current.from_asset_id == trade.fromAmount.asset.id.id &&
                current.from_amount == trade.fromAmount.amount.toString() &&
                current.to_account_id == trade.toAccountId.id &&
                current.to_asset_id == trade.toAmount.asset.id.id &&
                current.to_amount == trade.toAmount.amount.toString()
        if (reused != null && unchanged) return TradeCreateResult(reused, created = true)
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
        // A changed one is taken back unless the record now matches another trade, which it dedupes onto as a fresh import does.
        if (reused != null && current != null) {
            writeQueries.update(
                timestamp = timestamp,
                description = trade.description,
                from_account_id = trade.fromAccountId.id,
                from_asset_id = trade.fromAmount.asset.id.id,
                from_amount = trade.fromAmount.amount.toString(),
                to_account_id = trade.toAccountId.id,
                to_asset_id = trade.toAmount.asset.id.id,
                to_amount = trade.toAmount.amount.toString(),
                id = reused.id,
            )
            database.recordSource(
                deviceId,
                EntityType.TRADE,
                reused.id,
                selectQueries.selectRevision(reused.id).awaitAsOne(),
                trade.source,
            )
            return TradeCreateResult(reused, created = true)
        }
        // Allocate a transaction id (insert + last_insert_rowid must share a connection), unless a re-run reserved one.
        val id =
            trade.reuseId?.id ?: run {
                transactionIdWriteQueries.insert()
                transactionIdWriteQueries.lastInsertedId().awaitAsOne()
            }
        val revision =
            if (trade.reuseId == null) 1L else database.entitySourceSelectQueries.selectNextRevision(EntityType.TRADE.id, id).awaitAsOne()
        writeQueries.insert(
            id = id,
            revision_id = revision,
            timestamp = timestamp,
            description = trade.description,
            from_account_id = trade.fromAccountId.id,
            from_asset_id = trade.fromAmount.asset.id.id,
            from_amount = trade.fromAmount.amount.toString(),
            to_account_id = trade.toAccountId.id,
            to_asset_id = trade.toAmount.asset.id.id,
            to_amount = trade.toAmount.amount.toString(),
        )
        database.recordSource(deviceId, EntityType.TRADE, id, revision, trade.source)
        return TradeCreateResult(TradeId(id), created = true)
    }

    override suspend fun deleteTrades(ids: Collection<TradeId>): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.transaction {
                ids.forEach { writeQueries.delete(it.id) }
            }
        }

    override suspend fun updateTrades(edits: List<TradeEdit>): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.transaction {
                edits.forEach { edit ->
                    val trade = edit.trade
                    require(trade.from.asset.id != trade.to.asset.id) {
                        "A trade must exchange two different assets " +
                            "(from and to assets are both ${trade.from.asset.code})"
                    }
                    writeQueries.update(
                        timestamp = trade.timestamp.toEpochMilliseconds(),
                        description = trade.description,
                        from_account_id = trade.fromAccountId.id,
                        from_asset_id = trade.from.asset.id.id,
                        from_amount = trade.from.amount.toString(),
                        to_account_id = trade.toAccountId.id,
                        to_asset_id = trade.to.asset.id.id,
                        to_amount = trade.to.amount.toString(),
                        id = trade.id.id,
                    )
                    val revision = selectQueries.selectRevision(trade.id.id).awaitAsOneOrNull() ?: return@forEach
                    database.recordSource(deviceId, EntityType.TRADE, trade.id.id, revision, edit.source)
                }
            }
        }

    override suspend fun setAttributeByHand(
        id: TradeId,
        typeId: AttributeTypeId,
        value: String?,
        source: Source,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.transaction {
                val before = selectQueries.selectAttributeValue(id.id, typeId.id).awaitAsOneOrNull()
                if (before == value) return@transaction
                writeQueries.bumpRevision(id.id)
                val revision = selectQueries.selectRevision(id.id).awaitAsOneOrNull() ?: return@transaction
                if (value == null) {
                    writeQueries.deleteAttribute(id.id, typeId.id)
                } else {
                    writeQueries.upsertAttribute(id.id, typeId.id, value)
                }
                // audit_type ids: 1 INSERT (the new value), 2 UPDATE and 3 DELETE (the value before).
                val (auditType, auditValue) =
                    when {
                        before == null -> AUDIT_INSERT to checkNotNull(value)
                        value == null -> AUDIT_DELETE to before
                        else -> AUDIT_UPDATE to before
                    }
                writeQueries.insertAttributeAudit(
                    audit_timestamp = Clock.System.now().toEpochMilliseconds(),
                    audit_type_id = auditType,
                    trade_id = id.id,
                    revision_id = revision,
                    attribute_type_id = typeId.id,
                    attribute_value = auditValue,
                )
                database.recordSource(deviceId, EntityType.TRADE, id.id, revision, source)
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

private const val AUDIT_INSERT = 1L
private const val AUDIT_UPDATE = 2L
private const val AUDIT_DELETE = 3L
