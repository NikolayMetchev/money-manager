package com.moneymanager.domain.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

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

@Serializable
@JvmInline
value class TransferId(
    override val id: Long,
) : TransactionId {
    override fun toString() = id.toString()
}
