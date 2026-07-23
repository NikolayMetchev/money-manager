package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.repository.ExchangeOrderReadRepository
import kotlin.time.Instant

/**
 * Outcome of [ExchangeOrderWriteRepository.upsertOrder]: CREATED inserted a new order, UPDATED
 * revised an existing one (status/price fields changed since the last import), UNCHANGED matched an
 * identical existing row (the idempotent re-import path).
 */
data class OrderUpsertResult(
    val id: ExchangeOrderId,
    val outcome: Outcome,
) {
    enum class Outcome { CREATED, UPDATED, UNCHANGED }
}

interface ExchangeOrderWriteRepository : ExchangeOrderReadRepository {
    /**
     * Creates or revises the order identified by ([accountId], [orderRef]). An existing order with
     * different content is updated in place with a bumped revision (orders legitimately change
     * status between imports); an identical one is left untouched. Provenance is recorded for every
     * created/updated revision.
     */
    @Suppress("LongParameterList")
    suspend fun upsertOrder(
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
    ): OrderUpsertResult

    /** Links a fill trade to its order. Idempotent (INSERT OR IGNORE). */
    suspend fun linkTrade(
        orderId: ExchangeOrderId,
        tradeId: TradeId,
    )
}
