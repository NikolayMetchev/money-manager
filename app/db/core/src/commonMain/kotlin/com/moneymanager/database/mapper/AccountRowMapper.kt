@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object AccountRowMapper {
    @Suppress("LongParameterList")
    fun mapRaw(
        id: Long,
        timestamp: Long,
        description: String,
        accountId: Long,
        transactionAmount: Long,
        runningBalance: Long,
        currencyId: Long,
        currencyCode: String,
        currencyName: String,
        currencyScaleFactor: Long,
        sourceAccountId: Long,
        targetAccountId: Long,
        isExcluded: Long,
    ): AccountRow =
        AccountRow(
            transactionId = TransferId(id),
            timestamp = fromEpochMilliseconds(timestamp),
            description = description,
            accountId = AccountId(accountId),
            transactionAmount =
                Money(
                    transactionAmount,
                    Currency(
                        id = CurrencyId(currencyId),
                        code = currencyCode,
                        name = currencyName,
                        scaleFactor = currencyScaleFactor,
                    ),
                ),
            runningBalance =
                Money(
                    runningBalance,
                    Currency(
                        id = CurrencyId(currencyId),
                        code = currencyCode,
                        name = currencyName,
                        scaleFactor = currencyScaleFactor,
                    ),
                ),
            sourceAccountId = AccountId(sourceAccountId),
            targetAccountId = AccountId(targetAccountId),
            isExcluded = isExcluded != 0L,
        )
}
