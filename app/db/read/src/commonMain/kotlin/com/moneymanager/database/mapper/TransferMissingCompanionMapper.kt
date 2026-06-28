@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferMissingCompanion
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object TransferMissingCompanionMapper {
    @Suppress("LongParameterList")
    fun mapRaw(
        id: Long,
        timestamp: Long,
        description: String,
        amount: Long,
        matchValue: String,
        sourceAccountId: Long,
        sourceAccountName: String,
        targetAccountId: Long,
        targetAccountName: String,
        currencyId: Long,
        currencyCode: String,
        currencyName: String,
        currencyScaleFactor: Long,
    ): TransferMissingCompanion {
        val currency =
            Currency(
                id = CurrencyId(currencyId),
                code = currencyCode,
                name = currencyName,
                scaleFactor = currencyScaleFactor,
            )
        return TransferMissingCompanion(
            transferId = TransferId(id),
            matchValue = matchValue,
            timestamp = fromEpochMilliseconds(timestamp),
            description = description,
            sourceAccountId = AccountId(sourceAccountId),
            sourceAccountName = sourceAccountName,
            targetAccountId = AccountId(targetAccountId),
            targetAccountName = targetAccountName,
            amount = Money(amount, currency),
        )
    }
}
