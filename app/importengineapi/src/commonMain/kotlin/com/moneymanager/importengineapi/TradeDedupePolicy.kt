package com.moneymanager.importengineapi

import kotlin.time.Duration

/**
 * How the engine decides an incoming [ImportTradeIntent] is a trade the database already holds.
 *
 * Unlike a transfer, a matched trade cannot be "imported but tagged excluded and linked as
 * reconciled": `transfer_attribute` and `transfer_relationship` both reference `transfer(id)`, so a
 * trade can carry neither. The only mechanism available — and the one the exact-tuple path already
 * uses — is to suppress the write and report the existing trade's id, which surfaces the source row
 * as a duplicate pointing at the trade it duplicates.
 */
sealed interface TradeDedupePolicy {
    /**
     * Only the writer's exact-tuple match (timestamp to the millisecond, both accounts, both assets,
     * both amounts). Enough for re-importing the same file, and the default.
     */
    data object ExactTupleOnly : TradeDedupePolicy

    /**
     * Also matches a trade another source already recorded slightly differently. Two sources describing
     * one movement rarely agree on the exact tuple: an export stamps the second where an API stamps the
     * millisecond, and an export aggregates the fills an API reports one by one.
     *
     * @property window How far apart the two sources' timestamps for one movement may be.
     * @property allowAggregation Match one incoming trade against a *set* of existing trades whose
     *                            amounts sum to it — the per-fill case. The whole in-window candidate
     *                            set is tried as a unit; no subset search is attempted, so a partial
     *                            overlap deliberately does not match.
     * @property matchFromLegOnly Compare only the debited leg (account, asset and amount), ignoring the
     *                            credited amount. For sources whose credited amount is net of a charge
     *                            the other source reports gross — Binance's dust sweeps, where the CSV
     *                            credit is exactly 98% of the API trade's. Never enable it where the
     *                            debited leg alone could describe two genuinely different trades.
     */
    data class Fuzzy(
        val window: Duration,
        val allowAggregation: Boolean = true,
        val matchFromLegOnly: Boolean = false,
    ) : TradeDedupePolicy
}
