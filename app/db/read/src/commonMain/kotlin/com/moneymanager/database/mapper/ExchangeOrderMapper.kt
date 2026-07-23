package com.moneymanager.database.mapper

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ExchangeOrder
import com.moneymanager.domain.model.ExchangeOrderId
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object ExchangeOrderMapper {
    @Suppress("LongParameterList")
    fun mapRaw(
        id: Long,
        revisionId: Long,
        accountId: Long,
        orderRef: String,
        clientOid: String?,
        side: String,
        orderType: String?,
        timeInForce: String?,
        status: String?,
        limitPrice: String?,
        quantity: String?,
        avgPrice: String?,
        createdAt: Long,
        updatedAt: Long?,
    ): ExchangeOrder =
        ExchangeOrder(
            id = ExchangeOrderId(id),
            revisionId = revisionId,
            accountId = AccountId(accountId),
            orderRef = orderRef,
            clientOid = clientOid,
            side = side,
            orderType = orderType,
            timeInForce = timeInForce,
            status = status,
            limitPrice = limitPrice,
            quantity = quantity,
            avgPrice = avgPrice,
            createdAt = fromEpochMilliseconds(createdAt),
            updatedAt = updatedAt?.let(::fromEpochMilliseconds),
        )
}
