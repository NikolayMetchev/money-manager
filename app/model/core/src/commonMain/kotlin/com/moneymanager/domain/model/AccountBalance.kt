package com.moneymanager.domain.model

data class AccountBalance(
    val accountId: AccountId,
    val balance: Money,
)
