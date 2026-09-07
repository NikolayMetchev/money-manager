package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.TradeDedupePolicy
import kotlin.time.Instant

/**
 * Matches incoming trades against trades another source already recorded, so one movement described
 * by two sources is booked once.
 *
 * The exact-tuple match the writer performs cannot see these: an export stamps a trade to the second
 * where an API stamps it to the millisecond, and an export aggregates into one row the partial fills
 * an API reports individually. This reconciler closes that gap in memory over one preloaded window.
 *
 * A match **suppresses the write** and reports the existing trade's id. That is the only mechanism
 * available — a trade can carry neither an `excluded` attribute nor a `reconciled` relationship, since
 * both tables reference `transfer(id)` — and it is the same one the writer's own idempotency uses, so
 * the source row surfaces as a duplicate pointing at the trade it duplicates.
 *
 * Aggregation is matched in **both** directions, because either source can be the aggregating one:
 * one incoming trade against a set of existing ones (an export imported after the API), and — via
 * [incoming], which is why the whole batch is handed over up front — a set of incoming trades against
 * one existing trade (an API imported after the export, the far commoner order).
 *
 * Matched trades are **claimed**, so two incoming trades never both match the same existing one.
 * Claims live for one reconciler (one import), which is weaker than the transfer path's persisted
 * reconcile links: across separate files an existing trade could be claimed twice. That direction is
 * the safe one — over-suppression cannot invent money — and amount equality bounds the other.
 */
class TradeReconciler(
    private val policy: TradeDedupePolicy.Fuzzy,
    existing: List<Trade>,
    incoming: List<ImportTradeIntent> = emptyList(),
) {
    private data class BucketKey(
        val fromAccountId: AccountId,
        val toAccountId: AccountId,
        val fromAssetId: AssetId,
        val toAssetId: AssetId,
    )

    /** Candidates grouped by the fields any match requires, then ordered by time within a bucket. */
    private val buckets: Map<BucketKey, List<Trade>> =
        existing
            .groupBy { BucketKey(it.fromAccountId, it.toAccountId, it.from.asset.id, it.to.asset.id) }
            .mapValues { (_, trades) -> trades.sortedBy { it.timestamp } }

    private val claimed = mutableSetOf<TradeId>()

    /**
     * The fan-in case, resolved once over the whole batch: several incoming fills of one order against
     * the single aggregated trade the other source recorded for it. It cannot be decided one intent at
     * a time — no single fill equals the aggregate — so every member is mapped to the existing trade's
     * id here and [match] simply reads the answer off.
     *
     * The existing trade is claimed up front, so neither a later one-for-one match nor a second group
     * can also take it.
     */
    private val fanInMatches: Map<LocalTradeKey, TradeId> = buildFanInMatches(incoming)

    /**
     * The id of an existing trade (or the earliest of an existing set) that [intent] duplicates, or
     * null when nothing matches and the trade should be written.
     */
    fun match(intent: ImportTradeIntent): TradeId? {
        fanInMatches[intent.key]?.let { return it }
        val fromAccountId = intent.fromAccountId ?: return null
        val toAccountId = intent.toAccountId ?: return null
        val fromAmount = intent.fromAmount ?: return null
        val toAmount = intent.toAmount ?: return null
        val timestamp = intent.timestamp ?: return null

        val key = BucketKey(fromAccountId, toAccountId, fromAmount.asset.id, toAmount.asset.id)
        // The overwhelmingly common case is a movement no other source recorded: one hash miss and out,
        // before any window arithmetic.
        val candidates = buckets[key] ?: return null

        val inWindow = candidates.filter { it.id !in claimed && withinWindow(it.timestamp, timestamp) }
        if (inWindow.isEmpty()) return null

        matchOne(inWindow, fromAmount, toAmount, timestamp)?.let { return claim(listOf(it)) }
        if (policy.allowAggregation) {
            matchSet(inWindow, fromAmount, toAmount)?.let { return claim(it) }
        }
        return null
    }

    private fun withinWindow(
        candidate: Instant,
        incoming: Instant,
    ): Boolean = (candidate - incoming).absoluteValue <= policy.window

    /** A single existing trade describing the same movement; the nearest in time wins. */
    private fun matchOne(
        candidates: List<Trade>,
        fromAmount: Money,
        toAmount: Money,
        timestamp: Instant,
    ): Trade? =
        candidates
            .filter { amountsMatch(it.from, it.to, fromAmount, toAmount) }
            .minByOrNull { (it.timestamp - timestamp).absoluteValue }

    /**
     * The per-fill case: this source aggregated what the other reported one fill at a time. The whole
     * in-window candidate set is tried as a unit — because both sides derive from the same fills, their
     * totals agree exactly when they describe the same event. No subset search is attempted: it is
     * exponential, and a partial overlap is genuinely ambiguous, so it is left to book separately
     * rather than guessed at.
     */
    private fun matchSet(
        candidates: List<Trade>,
        fromAmount: Money,
        toAmount: Money,
    ): List<Trade>? {
        if (candidates.size < 2) return null
        val fromTotal = candidates.map { it.from }.reduce(Money::plus)
        val toTotal = candidates.map { it.to }.reduce(Money::plus)
        return candidates.takeIf { amountsMatch(fromTotal, toTotal, fromAmount, toAmount) }
    }

    private fun amountsMatch(
        candidateFrom: Money,
        candidateTo: Money,
        fromAmount: Money,
        toAmount: Money,
    ): Boolean =
        candidateFrom == fromAmount &&
            (policy.matchFromLegOnly || candidateTo == toAmount)

    /**
     * Groups the incoming trades by bucket and matches each existing candidate against the whole set of
     * incoming trades in its window — the mirror of [matchSet]. As there, the set is tried as a unit and
     * no subset search is attempted: both sides derive from the same fills, so their totals agree
     * exactly when they describe one event, and a partial overlap is genuinely ambiguous.
     *
     * A group whose member could equal a candidate on its own is left alone: that is the plain
     * one-for-one duplicate [matchOne] already handles, and treating it as a fill would consume the
     * wrong existing trade.
     */
    private fun buildFanInMatches(incoming: List<ImportTradeIntent>): Map<LocalTradeKey, TradeId> {
        if (!policy.allowAggregation || incoming.isEmpty()) return emptyMap()
        val incomingByBucket = incoming.mapNotNull(::fillOf).groupBy { it.bucket }
        if (incomingByBucket.isEmpty()) return emptyMap()
        val matches = mutableMapOf<LocalTradeKey, TradeId>()
        for ((bucket, group) in incomingByBucket) {
            for (candidate in buckets[bucket].orEmpty()) {
                val members = fillsSummingTo(candidate, group.filter { it.key !in matches }) ?: continue
                claimed += candidate.id
                members.forEach { matches[it.key] = candidate.id }
            }
        }
        return matches
    }

    /**
     * The fills of [candidate] among [group], or null when this is not the fan-in case: [candidate] is
     * already claimed, fewer than two fills sit in its window, they do not sum to it, or one of them
     * equals it on its own — the last being the plain one-for-one duplicate [matchOne] handles, which
     * as a fill would consume the wrong existing trade.
     */
    private fun fillsSummingTo(
        candidate: Trade,
        group: List<IncomingFill>,
    ): List<IncomingFill>? {
        if (candidate.id in claimed) return null
        val members = group.filter { withinWindow(candidate.timestamp, it.timestamp) }
        if (members.size < 2) return null
        if (members.any { matchOne(listOf(candidate), it.fromAmount, it.toAmount, it.timestamp) != null }) return null
        val fromTotal = members.map { it.fromAmount }.reduce(Money::plus)
        val toTotal = members.map { it.toAmount }.reduce(Money::plus)
        return members.takeIf { amountsMatch(candidate.from, candidate.to, fromTotal, toTotal) }
    }

    /** One incoming trade with every field a match needs resolved, or null when it is not comparable. */
    private data class IncomingFill(
        val key: LocalTradeKey,
        val bucket: BucketKey,
        val timestamp: Instant,
        val fromAmount: Money,
        val toAmount: Money,
    )

    private fun fillOf(intent: ImportTradeIntent): IncomingFill? {
        val fromAccountId = intent.fromAccountId ?: return null
        val toAccountId = intent.toAccountId ?: return null
        val fromAmount = intent.fromAmount ?: return null
        val toAmount = intent.toAmount ?: return null
        val timestamp = intent.timestamp ?: return null
        return IncomingFill(
            key = intent.key,
            bucket = BucketKey(fromAccountId, toAccountId, fromAmount.asset.id, toAmount.asset.id),
            timestamp = timestamp,
            fromAmount = fromAmount,
            toAmount = toAmount,
        )
    }

    private fun claim(trades: List<Trade>): TradeId {
        trades.forEach { claimed += it.id }
        return trades.minOf { it.id.id }.let(::TradeId)
    }
}
