@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant.Companion.fromEpochMilliseconds
import kotlin.uuid.Uuid

object TransferMapper {
    @Suppress("LongParameterList")
    fun mapRaw(
        id: String,
        revisionId: Long,
        timestamp: Long,
        description: String,
        sourceAccountId: Long,
        targetAccountId: Long,
        @Suppress("UNUSED_PARAMETER") currencyId: String,
        amount: Long,
        currency_id: String,
        currency_code: String,
        currency_name: String,
        currency_scaleFactor: Long,
    ): Transfer {
        val currency =
            Currency(
                id = CurrencyId(Uuid.parse(currency_id)),
                code = currency_code,
                name = currency_name,
                scaleFactor = currency_scaleFactor,
            )
        return Transfer(
            id = TransferId(Uuid.parse(id)),
            revisionId = revisionId,
            timestamp = fromEpochMilliseconds(timestamp),
            description = description,
            sourceAccountId = AccountId(sourceAccountId),
            targetAccountId = AccountId(targetAccountId),
            amount = Money(amount, currency),
            attributes = emptyList(),
        )
    }
}
