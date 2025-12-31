@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class Transfer(
    val id: TransferId,
    val revisionId: Long = 1,
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
    val amount: Money,
    val attributes: List<TransferAttribute> = emptyList(),
)

@JvmInline
value class TransferId(override val id: Uuid) : TransactionId {
    override fun toString() = id.toString()
}
