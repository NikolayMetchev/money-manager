@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Transfer(
    val id: Uuid,
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: Long,
    val targetAccountId: Long,
    val assetId: Long,
    val amount: Double,
)
