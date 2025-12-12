@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectRunningBalanceByAccount
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferId
import tech.mappie.api.ObjectMappie
import kotlin.time.Instant.Companion.fromEpochMilliseconds
import kotlin.uuid.Uuid

object AccountRowMapper : ObjectMappie<SelectRunningBalanceByAccount, AccountRow>() {
    override fun map(from: SelectRunningBalanceByAccount): AccountRow =
        mapping {
            AccountRow::transactionId fromValue TransferId(Uuid.parse(from.id))
            AccountRow::timestamp fromValue fromEpochMilliseconds(from.timestamp)
            AccountRow::accountId fromValue AccountId(from.accountId)
            AccountRow::transactionAmount fromValue Money(from.transactionAmount, from.toCurrency())
            AccountRow::runningBalance fromValue Money(from.runningBalance, from.toCurrency())
        }
}

private fun SelectRunningBalanceByAccount.toCurrency(): Currency =
    Currency(
        id = CurrencyId(Uuid.parse(currency_id)),
        code = currency_code,
        name = currency_name,
        scaleFactor = currency_scaleFactor,
    )
