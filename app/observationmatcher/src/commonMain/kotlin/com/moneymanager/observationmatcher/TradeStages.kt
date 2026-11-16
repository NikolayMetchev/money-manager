package com.moneymanager.observationmatcher

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.importengineapi.TradeDedupePolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The trade reconcilers that match one movement recorded in two shapes, as [MatchStage]s. They stay three: each
 * assigns by its own rule, and merging them would change what built-in strategies book.
 *
 * Every outcome is the same whichever source was imported first. Where the old reconcilers kept whichever record was
 * there first, the stages keep the more itemised one: a gross trade is excluded by its fills, and a conversion booked
 * as transfers by the trades that record it.
 */
object TradeStages {
    /**
     * The stages in the order they run. [tradePolicies] are the families whose trades are compared;
     * [conversionWindows] the reconcile windows of the leg-group rules whose conversions are compared with trades.
     */
    fun forPolicies(
        tradePolicies: Map<String, TradeDedupePolicy.Fuzzy>,
        conversionWindows: Map<LegGroupRuleRef, Duration>,
    ): List<MatchStage> =
        listOf(
            TradeAggregationStage(tradePolicies),
            ConversionGroupStage(conversionWindows),
            ConversionTradeStage(tradePolicies),
        )
}

/** A strategy's ([family]) leg-group rule, by name. */
data class LegGroupRuleRef(
    val family: String,
    val rule: String,
)

/**
 * `TradeReconciler`'s aggregation: one source reports an order as one gross trade, another as the fills it was made
 * of (Binance's export and its API). A trade is matched by every trade of one other family on the same accounts and
 * assets within the window, when there are at least two, none alone records it, and together they sum to it. The
 * gross trade is excluded by the fills.
 *
 * The window is that of the gross trade's family's policy, else the fills' family's: the old reconciler used the
 * policy of whichever was imported second.
 */
internal class TradeAggregationStage(
    policies: Map<String, TradeDedupePolicy.Fuzzy>,
) : MatchStage {
    private val policies = policies.filterValues { it.allowAggregation }
    private val width = slotWidth(this.policies.values.map { it.window })

    override val name = "trade aggregation"

    override fun scopeKeys(observation: MatchObservation): Collection<Any> {
        if (policies.isEmpty()) return emptyList()
        val trade = observation.trade ?: return emptyList()
        val pair = listOf(name, trade.fromAccountId, trade.toAccountId, trade.fromAssetId, trade.toAssetId)
        return slotsAround(trade.timestamp, width).map { pair + it }
    }

    override fun exclusions(
        transactions: List<MatchedTransaction>,
        claimed: Set<TransactionTarget>,
    ): List<StageMatch> {
        if (policies.isEmpty()) return emptyList()
        val trades = transactions.mapNotNull(::tradeOf)
        val byPair = trades.groupBy { it.pair }
        return linkEach(trades, claimed) { gross, taken ->
            if (gross.target in taken) return@linkEach null
            byPair
                .getValue(gross.pair)
                .filter { it.target !in taken && it.family != gross.family }
                .groupBy { it.family }
                .entries
                .sortedBy { it.key }
                .firstNotNullOfOrNull { (family, others) -> fillsOf(gross, family, others) }
        }
    }

    private fun fillsOf(
        gross: StageTrade,
        family: String,
        others: List<StageTrade>,
    ): StageMatch? {
        val policy = policies[gross.family] ?: policies[family] ?: return null
        val fills = others.filter { (it.values.timestamp - gross.values.timestamp).absoluteValue <= policy.window }
        if (fills.size < 2 || fills.any { it.records(gross.values, policy) }) return null
        val from = fills.fold(BigInteger.ZERO) { sum, fill -> sum + fill.values.fromAmount }
        val to = fills.fold(BigInteger.ZERO) { sum, fill -> sum + fill.values.toAmount }
        val sums =
            from.compareTo(gross.values.fromAmount) == 0 &&
                (policy.matchFromLegOnly || to.compareTo(gross.values.toAmount) == 0)
        return if (sums) StageMatch(listOf(gross.transaction), fills.map { it.transaction }) else null
    }
}

/**
 * `ConversionGroupReconciler`: a through-account conversion group (Binance's dust sweep, booked from the export as
 * transfers through a conversion account) that a source also reports as trades. The group matches when each of its
 * debit legs finds its own trade from the same account, of the same asset and amount, within the leg-group rule's
 * window; the credits are not compared (the export nets the fee where the trades don't). Then the whole group, debits
 * and credits, is excluded by those trades. One unmatched debit and nothing is linked: excluding part of a group would
 * corrupt the balances it protects.
 */
internal class ConversionGroupStage(
    private val windows: Map<LegGroupRuleRef, Duration>,
) : MatchStage {
    private val width = slotWidth(windows.values)

    override val name = "conversion group"

    override fun scopeKeys(observation: MatchObservation): Collection<Any> {
        if (windows.isEmpty()) return emptyList()
        observation.trade?.let { return debitKeys(name, it.fromAccountId, it.fromAssetId, it.fromAmount, it.timestamp, width) }
        val leg = observation.legGroup ?: return emptyList()
        if (LegGroupRuleRef(observation.family, leg.rule) !in windows) return emptyList()
        val group = listOf(name, observation.family, leg.rule, leg.key)
        val transfer = observation.transfer
        if (leg.side != LegGroupSide.DEBIT || transfer == null) return listOf(group)
        return debitKeys(name, transfer.sourceAccountId, transfer.assetId, transfer.amount, transfer.timestamp, width) + listOf(group)
    }

    override fun exclusions(
        transactions: List<MatchedTransaction>,
        claimed: Set<TransactionTarget>,
    ): List<StageMatch> {
        if (windows.isEmpty()) return emptyList()
        val trades = transactions.mapNotNull(::tradeOf).groupBy { it.debit }
        return linkEach(legGroupsOf(transactions), claimed) { group, taken ->
            val window = windows[group.rule]
            if (window == null || group.legs.any { it.target in taken }) return@linkEach null
            val matched = mutableListOf<StageTrade>()
            val complete =
                group.debits.all { debit ->
                    val trade: StageTrade? =
                        trades[debit.key]
                            .orEmpty()
                            .filter { it.target !in taken && it !in matched }
                            .map { it to (it.values.timestamp - debit.timestamp).absoluteValue }
                            .filter { (_, apart) -> apart <= window }
                            .minByOrNull { (_, apart) -> apart }
                            ?.first
                    if (trade != null) matched += trade
                    trade != null
                }
            if (complete && matched.isNotEmpty()) StageMatch(group.legs.map { it.transaction }, matched.map { it.transaction }) else null
        }
    }
}

/**
 * `ConversionTradeReconciler`: a trade a source reports for a conversion another booked as `conversion`-linked
 * transfers (Binance's export books a dust sweep as transfers, its API as trades). A trade matches a debit leg from
 * its account, of its asset and amount, whose group credits the trade's credited asset, within its family's window.
 * Trades take legs in time order, each the earliest leg still free, and each is excluded by its leg. A trade an
 * exchange order claims never matches: the order would be left pointing at an excluded trade.
 *
 * It finds what the [ConversionGroupStage] leaves: a group only some of whose debits a source reports as trades.
 */
internal class ConversionTradeStage(
    policies: Map<String, TradeDedupePolicy.Fuzzy>,
) : MatchStage {
    private val policies = policies.filterValues { it.conversionRelationshipTypeName != null }
    private val relationshipTypes = this.policies.values.mapNotNullTo(mutableSetOf()) { it.conversionRelationshipTypeName }
    private val width = slotWidth(this.policies.values.map { it.window })

    override val name = "conversion trade"

    override fun scopeKeys(observation: MatchObservation): Collection<Any> {
        if (policies.isEmpty()) return emptyList()
        observation.trade?.let { trade ->
            if (observation.family !in policies || observation.orderLinked) return emptyList()
            return debitKeys(name, trade.fromAccountId, trade.fromAssetId, trade.fromAmount, trade.timestamp, width)
        }
        val leg = observation.legGroup ?: return emptyList()
        if (leg.relationshipType !in relationshipTypes) return emptyList()
        val group = listOf(name, observation.family, leg.rule, leg.key)
        val transfer = observation.transfer
        if (leg.side != LegGroupSide.DEBIT || transfer == null) return listOf(group)
        return debitKeys(name, transfer.sourceAccountId, transfer.assetId, transfer.amount, transfer.timestamp, width) + listOf(group)
    }

    override fun exclusions(
        transactions: List<MatchedTransaction>,
        claimed: Set<TransactionTarget>,
    ): List<StageMatch> {
        if (policies.isEmpty()) return emptyList()
        val legs =
            legGroupsOf(transactions)
                .flatMap { group ->
                    group.debits.flatMap { debit ->
                        group.credits
                            .map { it.assetId }
                            .distinct()
                            .map { credit -> group.relationshipType to (debit.key to credit) to debit }
                    }
                }.groupBy({ it.first }, { it.second })
        val trades =
            transactions
                .filter { transaction -> transaction.members.none { it.orderLinked } }
                .mapNotNull(::tradeOf)
                .filter { it.family in policies }
                .withIndex()
                .sortedWith(compareBy({ it.value.values.timestamp }, { it.index }))
                .map { it.value }
        return linkEach(trades, claimed) { trade, taken ->
            val policy = policies.getValue(trade.family)
            val relationshipType = policy.conversionRelationshipTypeName
            if (trade.target in taken || relationshipType == null) return@linkEach null
            legs[relationshipType to (trade.debit to trade.values.toAssetId)]
                .orEmpty()
                .filter { it.target !in taken && (it.timestamp - trade.values.timestamp).absoluteValue <= policy.window }
                .minByOrNull { it.timestamp }
                ?.let { leg -> StageMatch(listOf(trade.transaction), listOf(leg.transaction)) }
        }
    }
}

/**
 * Asks [linkFor] for each of [items] in turn, with the transactions taken so far ([claimed] and those of every link
 * found before), so no transaction is in two links.
 */
private fun <T> linkEach(
    items: List<T>,
    claimed: Set<TransactionTarget>,
    linkFor: (T, Set<TransactionTarget>) -> StageMatch?,
): List<StageMatch> {
    val taken = claimed.toMutableSet()
    return items.mapNotNull { item -> linkFor(item, taken)?.also { taken += it.targets } }
}

/** A debit: an amount of an asset leaving an account. */
private data class DebitKey(
    val accountId: AccountId,
    val assetId: ObservedAssetId,
    val amount: String,
)

private fun debitKeyOf(
    accountId: AccountId,
    assetId: ObservedAssetId,
    amount: BigInteger,
) = DebitKey(accountId, assetId, amount.toString())

/** The trade a transaction records, read from its first trade observation. */
private class StageTrade(
    val transaction: MatchedTransaction,
    val family: String,
    val values: ObservedValues.Trade,
) {
    val target get() = transaction.target
    val pair = listOf(values.fromAccountId, values.toAccountId, values.fromAssetId, values.toAssetId)
    val debit = debitKeyOf(values.fromAccountId, values.fromAssetId, values.fromAmount)

    /** Whether this trade alone records [other], as the one-for-one match would take it. */
    fun records(
        other: ObservedValues.Trade,
        policy: TradeDedupePolicy.Fuzzy,
    ): Boolean =
        values.fromAmount.compareTo(other.fromAmount) == 0 &&
            (policy.matchFromLegOnly || values.toAmount.compareTo(other.toAmount) == 0)
}

private fun tradeOf(transaction: MatchedTransaction): StageTrade? {
    val (member, trade) = transaction.members.firstNotNullOfOrNull { member -> member.trade?.let { member to it } } ?: return null
    return StageTrade(transaction, member.family, trade)
}

/** One leg of a [StageLegGroup]: a transaction and the transfer it records. */
private class StageLeg(
    val transaction: MatchedTransaction,
    transfer: ObservedValues.Transfer,
) {
    val target get() = transaction.target
    val timestamp: Instant = transfer.timestamp
    val assetId = transfer.assetId
    val key = debitKeyOf(transfer.sourceAccountId, transfer.assetId, transfer.amount)
}

/** The transactions one leg group's legs are on, in the order of their first appearance. */
private class StageLegGroup(
    val rule: LegGroupRuleRef,
    val relationshipType: String,
    val debits: List<StageLeg>,
    val credits: List<StageLeg>,
) {
    val legs get() = debits + credits
}

private fun legGroupsOf(transactions: List<MatchedTransaction>): List<StageLegGroup> {
    data class Key(
        val family: String,
        val rule: String,
        val key: String,
        val relationshipType: String,
    )
    val sides = linkedMapOf<Key, Pair<MutableList<StageLeg>, MutableList<StageLeg>>>()
    for (transaction in transactions) {
        val (member, leg, transfer) =
            transaction.members.firstNotNullOfOrNull { member ->
                val leg = member.legGroup ?: return@firstNotNullOfOrNull null
                member.transfer?.let { Triple(member, leg, it) }
            } ?: continue
        val (debits, credits) =
            sides.getOrPut(Key(member.family, leg.rule, leg.key, leg.relationshipType)) { mutableListOf<StageLeg>() to mutableListOf() }
        val side = if (leg.side == LegGroupSide.DEBIT) debits else credits
        side += StageLeg(transaction, transfer)
    }
    return sides.map { (key, legs) -> StageLegGroup(LegGroupRuleRef(key.family, key.rule), key.relationshipType, legs.first, legs.second) }
}

/**
 * Scope keys are coarse time slots: two observations at most a [slotWidth] apart share one, since each takes its own
 * slot and both neighbours.
 */
private fun slotWidth(windows: Collection<Duration>): Duration = (windows + 1.days).max()

private fun slotsAround(
    timestamp: Instant,
    width: Duration,
): List<Long> {
    val slot = timestamp.epochSeconds.floorDiv(width.inWholeSeconds)
    return listOf(slot - 1, slot, slot + 1)
}

private fun debitKeys(
    stage: String,
    accountId: AccountId,
    assetId: ObservedAssetId,
    amount: BigInteger,
    timestamp: Instant,
    width: Duration,
): List<Any> {
    val debit = debitKeyOf(accountId, assetId, amount)
    return slotsAround(timestamp, width).map { listOf(stage, debit, it) }
}
