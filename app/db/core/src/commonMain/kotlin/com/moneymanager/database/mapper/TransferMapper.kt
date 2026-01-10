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
        revision_id: Long,
        timestamp: Long,
        description: String,
        source_account_id: Long,
        target_account_id: Long,
        amount: Long,
        currency_id: Long,
        currency_code: String,
        currency_name: String,
        currency_scale_factor: Long,
    ): Transfer {
        val currency =
            Currency(
                id = CurrencyId(currency_id),
                code = currency_code,
                name = currency_name,
                scaleFactor = currency_scale_factor,
            )
        return Transfer(
            id = TransferId(id),
            revisionId = revision_id,
            timestamp = fromEpochMilliseconds(timestamp),
            description = description,
            sourceAccountId = AccountId(source_account_id),
            targetAccountId = AccountId(target_account_id),
            amount = Money(amount, currency),
            attributes = emptyList(),
        )
    }
}
