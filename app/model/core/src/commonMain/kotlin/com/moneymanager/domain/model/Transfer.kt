@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class Transfer(
    val id: Uuid,
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: Long,
    val targetAccountId: Long,
    val currencyId: CurrencyId,
    val amount: Double,
)
