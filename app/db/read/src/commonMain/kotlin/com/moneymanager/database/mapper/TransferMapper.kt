@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object TransferMapper {
    @Suppress("LongParameterList")
    fun mapRaw(
        id: Long,
        revisionId: Long,
        timestamp: Long,
        description: String,
        sourceAccountId: Long,
        targetAccountId: Long,
        amount: Long,
        currencyId: Long,
        currencyCode: String,
        currencyName: String,
        currencyScaleFactor: Long,
    ): Transfer {
        val currency =
            Currency(
                id = CurrencyId(currencyId),
                code = currencyCode,
                name = currencyName,
                scaleFactor = currencyScaleFactor,
            )
        return Transfer(
            id = TransferId(id),
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
