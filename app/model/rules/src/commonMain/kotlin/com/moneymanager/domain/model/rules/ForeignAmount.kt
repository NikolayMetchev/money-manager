package com.moneymanager.domain.model.rules

import kotlinx.serialization.Serializable

/**
 * What the counterparty was actually paid, when the statement account settled the movement in another
 * currency — a GBP card charged for a USD purchase reports both the USD 13.84 it paid and the GBP 10.98 it
 * cost. The one foreign-amount vocabulary for CSV amount mappings and API transactions.
 *
 * When [currency] resolves to an asset other than the settled one and [amount] is non-zero, the movement
 * is booked in the foreign currency and the statement account's conversion (settled → foreign, or the
 * reverse for a refund) as a trade on it. So the merchant receives what it was paid, and another source
 * recording the same money in that currency — PayPal's own export of the payment — can reconcile with it.
 * Otherwise the movement stays in the settled currency.
 *
 * @property amount The foreign amount; its sign is ignored (the settled amount gives the direction).
 * @property currency The foreign amount's asset code.
 */
@Serializable
data class ForeignAmount(
    val amount: ValueExpr,
    val currency: ValueExpr,
)
