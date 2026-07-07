@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A cross-asset exchange transaction — a second transaction subtype alongside [Transfer], both
 * drawing their id from the shared `transaction_id` space. A trade moves [from] out of
 * [fromAccountId] and [to] into [toAccountId]; the two [Money] legs may be denominated in different
 * [Asset]s (e.g. £100 out of a bank account, 5 BNB into a wallet). The implied rate is
 * `to.amount / from.amount`.
 *
 * Unlike a [Transfer] (one asset, one amount for both legs), a trade honours double-entry across
 * assets — which is what a crypto buy / FX conversion genuinely is.
 */
data class Trade(
    val id: TradeId,
    val revisionId: Long = 1,
    val timestamp: Instant,
    val description: String,
    val fromAccountId: AccountId,
    val from: Money,
    val toAccountId: AccountId,
    val to: Money,
    val attributes: List<TransferAttribute> = emptyList(),
)

@Serializable
@JvmInline
value class TradeId(
    override val id: Long,
) : TransactionId {
    override fun toString() = id.toString()
}
