@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAllBalances
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import tech.mappie.api.ObjectMappie
import kotlin.uuid.Uuid

object AccountBalanceMapper : ObjectMappie<SelectAllBalances, AccountBalance>(), IdConversions {
    override fun map(from: SelectAllBalances): AccountBalance =
        mapping {
            AccountBalance::balance fromValue Money(from.balance, from.toCurrency())
        }
}

private fun SelectAllBalances.toCurrency(): Currency =
    Currency(
        id = CurrencyId(Uuid.parse(currency_id)),
        code = currency_code,
        name = currency_name,
        scaleFactor = currency_scaleFactor,
    )
