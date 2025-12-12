@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class AccountRow(
    val transactionId: TransactionId,
    val timestamp: Instant,
    val description: String,
    val accountId: AccountId,
    val transactionAmount: Money,
    val runningBalance: Money,
)
