@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class Transfer(
    val id: TransferId,
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
    val currencyId: CurrencyId,
    val amount: Double,
)

@JvmInline
value class TransferId(override val id: Uuid) : TransactionId {
    override fun toString() = id.toString()
}
