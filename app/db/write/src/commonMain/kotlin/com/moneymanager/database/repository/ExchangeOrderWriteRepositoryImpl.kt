@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.recordSource
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.repository.ExchangeOrderReadRepository
import com.moneymanager.domain.repository.ExchangeOrderWriteRepository
import com.moneymanager.domain.repository.OrderUpsertResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class ExchangeOrderWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: ExchangeOrderReadRepository,
) : ExchangeOrderWriteRepository,
    ExchangeOrderReadRepository by reader {
    private val writeQueries = database.exchangeOrderWriteQueries
    private val selectQueries = database.exchangeOrderSelectQueries

    override suspend fun upsertOrder(
        accountId: AccountId,
        orderRef: String,
        clientOid: String?,
        side: String,
        orderType: String?,
        timeInForce: String?,
        status: String?,
        limitPrice: String?,
        quantity: String?,
        avgPrice: String?,
        createdAt: Instant,
        updatedAt: Instant?,
        source: Source,
    ): OrderUpsertResult =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                val existing = selectQueries.selectByAccountAndOrderRef(accountId.id, orderRef).executeAsOneOrNull()
                when {
                    existing == null -> {
                        writeQueries.insert(
                            account_id = accountId.id,
                            order_ref = orderRef,
                            client_oid = clientOid,
                            side = side,
                            order_type = orderType,
                            time_in_force = timeInForce,
                            status = status,
                            limit_price = limitPrice,
                            quantity = quantity,
                            avg_price = avgPrice,
                            created_at = createdAt.toEpochMilliseconds(),
                            updated_at = updatedAt?.toEpochMilliseconds(),
                        )
                        val id = writeQueries.lastInsertedId().executeAsOne()
                        database.recordSource(deviceId, EntityType.EXCHANGE_ORDER, id, 1L, source)
                        OrderUpsertResult(ExchangeOrderId(id), OrderUpsertResult.Outcome.CREATED)
                    }
                    existing.client_oid == clientOid &&
                        existing.side == side &&
                        existing.order_type == orderType &&
                        existing.time_in_force == timeInForce &&
                        existing.status == status &&
                        existing.limit_price == limitPrice &&
                        existing.quantity == quantity &&
                        existing.avg_price == avgPrice &&
                        existing.created_at == createdAt.toEpochMilliseconds() &&
                        existing.updated_at == updatedAt?.toEpochMilliseconds() -> {
                        OrderUpsertResult(ExchangeOrderId(existing.id), OrderUpsertResult.Outcome.UNCHANGED)
                    }
                    else -> {
                        writeQueries.updateByUniqueKey(
                            client_oid = clientOid,
                            side = side,
                            order_type = orderType,
                            time_in_force = timeInForce,
                            status = status,
                            limit_price = limitPrice,
                            quantity = quantity,
                            avg_price = avgPrice,
                            created_at = createdAt.toEpochMilliseconds(),
                            updated_at = updatedAt?.toEpochMilliseconds(),
                            account_id = accountId.id,
                            order_ref = orderRef,
                        )
                        database.recordSource(deviceId, EntityType.EXCHANGE_ORDER, existing.id, existing.revision_id + 1, source)
                        OrderUpsertResult(ExchangeOrderId(existing.id), OrderUpsertResult.Outcome.UPDATED)
                    }
                }
            }
        }

    override suspend fun linkTrade(
        orderId: ExchangeOrderId,
        tradeId: TradeId,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.linkTrade(orderId.id, tradeId.id)
        }
}
