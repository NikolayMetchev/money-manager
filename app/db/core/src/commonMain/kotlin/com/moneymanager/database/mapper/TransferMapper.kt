@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAll
import com.moneymanager.database.sql.SelectByDateRange
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import tech.mappie.api.ObjectMappie
import kotlin.uuid.Uuid

object TransferMapper :
    ObjectMappie<SelectAll, Transfer>(),
    IdConversions,
    InstantConversions {
    override fun map(from: SelectAll): Transfer =
        mapping {
            Transfer::amount fromValue Money(from.amount, from.toCurrency())
        }

    @Suppress("LongParameterList")
    fun mapRaw(
        id: String,
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
    ): Transfer =
        map(
            SelectAll(
                id = id,
                timestamp = timestamp,
                description = description,
                sourceAccountId = sourceAccountId,
                targetAccountId = targetAccountId,
                currencyId = currencyId,
                amount = amount,
                currency_id = currency_id,
                currency_code = currency_code,
                currency_name = currency_name,
                currency_scaleFactor = currency_scaleFactor,
            ),
        )
}

private fun SelectAll.toCurrency(): Currency =
    Currency(
        id = CurrencyId(Uuid.parse(currency_id)),
        code = currency_code,
        name = currency_name,
        scaleFactor = currency_scaleFactor,
    )

private fun SelectByDateRange.toCurrency(): Currency =
    Currency(
        id = CurrencyId(Uuid.parse(currency_id)),
        code = currency_code,
        name = currency_name,
        scaleFactor = currency_scaleFactor,
    )
