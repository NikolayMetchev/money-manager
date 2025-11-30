@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class Transaction(
    val id: Long = 0,
    val sourceAccountId: Long,
    val targetAccountId: Long? = null,
    val assetId: Long,
    val timestamp: Instant,
)
