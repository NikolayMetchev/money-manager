package com.moneymanager.observationmatcher

import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.importengineapi.TradeDedupePolicy

/**
 * The rules that make two trade observations one trade, the importer's `createTrades` exact match and
 * `TradeReconciler`'s one-for-one match as pair rules. Trade windows stay clock time: exchanges stamp trades to the
 * second or the millisecond, never to a business day.
 *
 * `TradeReconciler`'s aggregation and the two conversion reconcilers change the shape of what is counted, so they are
 * [MatchStage]s: see [TradeStages].
 */
object TradeRules {
    /** Every trade pair rule, in rank order; [policies] are the families whose trades a [FuzzyTradeRule] compares. */
    fun forPolicies(policies: Map<String, TradeDedupePolicy.Fuzzy>): List<PairRule> = listOf(TradeTupleRule, FuzzyTradeRule(policies))
}

/**
 * Every field of a trade equal but the description: the time to the millisecond (what a trade row keeps), both
 * accounts, both assets and both amounts. Two sources wording one conversion differently (Crypto.com's fiat and
 * crypto exports of one viban purchase) are one trade, whatever their families, and so is a trade downloaded again in
 * another session.
 *
 * Never within one container: an exchange can split an order into fills identical to the millisecond (Kraken), and
 * those are as many trades. Across two containers each fill pairs with one other, as complete linkage stops a group
 * holding two fills of one container: the `occurrence` counter of the old exact match.
 */
internal object TradeTupleRule : PairRule {
    override val name = "trade tuple"
    override val joinsSameContainer = false

    override fun bucket(observation: MatchObservation): Any? {
        val trade = observation.trade ?: return null
        return listOf(
            trade.timestamp.toEpochMilliseconds(),
            trade.fromAccountId,
            trade.fromAssetId,
            trade.fromAmount.toString(),
            trade.toAccountId,
            trade.toAssetId,
            trade.toAmount.toString(),
        )
    }

    override fun distance(
        a: MatchObservation,
        b: MatchObservation,
    ): Long = 0
}

/**
 * `TradeReconciler`'s one-for-one match: one trade two sources stamp differently (an export to the second, an API to
 * the millisecond). The same accounts, assets and debited amount, the credited amount too unless the policy compares
 * the debited leg only, and times within the policy's window; the nearest pair is taken first.
 *
 * It pairs a trade of a family with a [TradeDedupePolicy.Fuzzy] policy with any other trade, as the old reconciler
 * matched an import's trades against every source's. When both families have a policy, either one accepting the pair
 * is enough, so the outcome no longer depends on which of the two was imported second.
 */
internal class FuzzyTradeRule(
    private val policies: Map<String, TradeDedupePolicy.Fuzzy>,
) : PairRule {
    override val name = "fuzzy trade"
    override val joinsSameContainer = false

    override fun bucket(observation: MatchObservation): Any? {
        if (policies.isEmpty()) return null
        val trade = observation.trade ?: return null
        return listOf(trade.fromAccountId, trade.toAccountId, trade.fromAssetId, trade.toAssetId, trade.fromAmount.toString())
    }

    override fun distance(
        a: MatchObservation,
        b: MatchObservation,
    ): Long? {
        val x = a.trade ?: return null
        val y = b.trade ?: return null
        val apart = (x.timestamp - y.timestamp).absoluteValue
        val accepted =
            listOfNotNull(policies[a.family], policies[b.family]).any { policy ->
                apart <= policy.window && (policy.matchFromLegOnly || x.toAmount.compareTo(y.toAmount) == 0)
            }
        return if (accepted) apart.inWholeSeconds else null
    }
}

internal val MatchObservation.trade: ObservedValues.Trade? get() = content.values as? ObservedValues.Trade

internal val MatchObservation.transfer: ObservedValues.Transfer? get() = content.values as? ObservedValues.Transfer
