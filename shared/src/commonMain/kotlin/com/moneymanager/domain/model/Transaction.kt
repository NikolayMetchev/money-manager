@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class Transaction(
    val id: Long = 0,
    val accountId: Long,
    val categoryId: Long? = null,
    val type: TransactionType,
    val amount: Double,
    val currency: String = "USD",
    val description: String? = null,
    val note: String? = null,
    val transactionDate: Instant,
    val toAccountId: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
