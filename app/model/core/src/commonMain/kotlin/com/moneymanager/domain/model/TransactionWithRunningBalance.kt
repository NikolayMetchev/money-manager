@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class TransactionWithRunningBalance(
    val transactionId: Long,
    val timestamp: Instant,
    val description: String,
    val accountId: Long,
    val assetId: Long,
    val transactionAmount: Double,
    val runningBalance: Double,
)
