package com.moneymanager.domain.model

data class AccountBalance(
    val accountId: Long,
    val assetId: Long,
    val balance: Double,
)
