@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.uuid.Uuid

data class AccountBalance(
    val accountId: Long,
    val currencyId: Uuid,
    val balance: Double,
)
