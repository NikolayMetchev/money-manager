@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

data class AccountBalance(
    val accountId: Long,
    val currencyId: CurrencyId,
    val balance: Double,
)
