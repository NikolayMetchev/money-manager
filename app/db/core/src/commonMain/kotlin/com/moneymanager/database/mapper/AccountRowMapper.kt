@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant.Companion.fromEpochMilliseconds
import kotlin.uuid.Uuid

object AccountRowMapper {
    fun mapRaw(
        id: String,
        timestamp: Long,
        description: String,
        accountId: Long,
        @Suppress("UnusedParameter") currencyId: String,
        transactionAmount: Long,
        runningBalance: Long,
        currency_id: String,
        currency_code: String,
        currency_name: String,
        currency_scaleFactor: Long,
        sourceAccountId: Long,
        targetAccountId: Long,
    ): AccountRow =
        AccountRow(
            transactionId = TransferId(Uuid.parse(id)),
            timestamp = fromEpochMilliseconds(timestamp),
            description = description,
            accountId = AccountId(accountId),
            transactionAmount =
                Money(
                    transactionAmount,
                    Currency(
                        id = CurrencyId(Uuid.parse(currency_id)),
                        code = currency_code,
                        name = currency_name,
                        scaleFactor = currency_scaleFactor,
                    ),
                ),
            runningBalance =
                Money(
                    runningBalance,
                    Currency(
                        id = CurrencyId(Uuid.parse(currency_id)),
                        code = currency_code,
                        name = currency_name,
                        scaleFactor = currency_scaleFactor,
                    ),
                ),
            sourceAccountId = AccountId(sourceAccountId),
            targetAccountId = AccountId(targetAccountId),
        )
}
