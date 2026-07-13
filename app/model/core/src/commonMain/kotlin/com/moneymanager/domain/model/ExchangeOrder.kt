@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * An order placed on a crypto exchange (e.g. Crypto.com's `private/get-order-history`). Orders are
 * metadata, not transactions: a filled order's economics are fully represented by its fill [Trade]s
 * (linked via `exchange_order_trade`), so an order never participates in balances. Unlike trades,
 * an order's [status]/[avgPrice]/[quantity] legitimately change between imports (ACTIVE → FILLED /
 * CANCELED), so re-import upserts by ([accountId], [orderRef]) and bumps [revisionId].
 *
 * Price/quantity fields are exchange-reported decimal strings, display-only — they are never used
 * in arithmetic, so they stay in whatever scale the exchange reported.
 */
data class ExchangeOrder(
    val id: ExchangeOrderId,
    val revisionId: Long = 1,
    val accountId: AccountId,
    val orderRef: String,
    val clientOid: String? = null,
    val side: String,
    val orderType: String? = null,
    val timeInForce: String? = null,
    val status: String? = null,
    val limitPrice: String? = null,
    val quantity: String? = null,
    val avgPrice: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)

@Serializable
@JvmInline
value class ExchangeOrderId(
    val id: Long,
) {
    override fun toString() = id.toString()
}
