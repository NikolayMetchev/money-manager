@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class Transfer(
    val id: Long,
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: Long,
    val targetAccountId: Long,
    val assetId: Long,
    val amount: Double,
)
