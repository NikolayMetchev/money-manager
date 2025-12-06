@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class AccountRow(
    val transactionId: Uuid,
    val timestamp: Instant,
    val description: String,
    val accountId: AccountId,
    val currencyId: CurrencyId,
    val transactionAmount: Double,
    val runningBalance: Double,
)
