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
        account_id: Long,
        transaction_amount: Long,
        running_balance: Long,
        currency_id: String,
        currency_code: String,
        currency_name: String,
        currency_scale_factor: Long,
        source_account_id: Long,
        target_account_id: Long,
    ): AccountRow =
        AccountRow(
            transactionId = TransferId(Uuid.parse(id)),
            timestamp = fromEpochMilliseconds(timestamp),
            description = description,
            accountId = AccountId(account_id),
            transactionAmount =
                Money(
                    transaction_amount,
                    Currency(
                        id = CurrencyId(Uuid.parse(currency_id)),
                        code = currency_code,
                        name = currency_name,
                        scaleFactor = currency_scale_factor,
                    ),
                ),
            runningBalance =
                Money(
                    running_balance,
                    Currency(
                        id = CurrencyId(Uuid.parse(currency_id)),
                        code = currency_code,
                        name = currency_name,
                        scaleFactor = currency_scale_factor,
                    ),
                ),
            sourceAccountId = AccountId(source_account_id),
            targetAccountId = AccountId(target_account_id),
        )
}
