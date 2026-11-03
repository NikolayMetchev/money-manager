package com.moneymanager.reconciliation

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.tasks.Progress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Source legs paired with the real legs recording the same movement: usually one each, but several when
 * the two sides split a movement differently (e.g. the source books a trade and its fee as two legs, the
 * real account one gross leg) — then the legs on each side share a timestamp and sum to the same amount.
 */
data class LegMatch(
    val sourceLegs: List<ReconciliationLeg>,
    val realLegs: List<ReconciliationLeg>,
    /** The "reconciliation-match" relationship a person stored this match as; null when the matcher found it. */
    val matchId: Long? = null,
) {
    constructor(sourceLeg: ReconciliationLeg, realLeg: ReconciliationLeg) : this(listOf(sourceLeg), listOf(realLeg))

    val sourceLeg: ReconciliationLeg get() = sourceLegs.first()
    val realLeg: ReconciliationLeg get() = realLegs.first()

    /** Exact = one leg each at the same second; otherwise found by a later pass. */
    val exact: Boolean
        get() = sourceLegs.size == 1 && realLegs.size == 1 && sourceLeg.timestamp.epochSeconds == realLeg.timestamp.epochSeconds

    /** Settled: a person stored it, or it agrees to the second. Anything else is a near match to accept. */
    val settled: Boolean get() = matchId != null || exact

    val timeDelta: Duration get() = realLeg.timestamp - sourceLeg.timestamp
}

/** A source leg nothing in Money Manager records. */
data class UnmatchedSourceLeg(
    val leg: ReconciliationLeg,
    /** The shadow (wallet) account the leg sits on. */
    val walletAccountId: AccountId,
    /**
     * The leg's asset never occurs on the linked real accounts — typically a spam airdrop the source
     * tracks but nobody would import, so the UI can hide these by default.
     */
    val assetUnknownToMm: Boolean,
)

/** A real leg the source doesn't record. */
data class UnmatchedRealLeg(
    val leg: ReconciliationLeg,
    /** The shadow (wallet) account the real account is linked to. */
    val walletAccountId: AccountId,
)

data class ReconciliationResult(
    /** The span of the source's data; real legs outside it are out of scope. Null when there is none. */
    val sourceRange: ClosedRange<Instant>?,
    val matches: List<LegMatch>,
    /** Source legs to import into Money Manager. */
    val missingInMm: List<UnmatchedSourceLeg>,
    /** Real legs to import into the source. */
    val missingInSource: List<UnmatchedRealLeg>,
    /** Source legs on the linked wallets that a person ignored (see `ReconciliationLeg.isIgnored`). */
    val ignored: List<UnmatchedSourceLeg> = emptyList(),
    /**
     * Every in-scope real leg no match claims, whatever its date: what a source leg may be matched to by hand. Unlike
     * [missingInSource] it isn't limited to the source's date range, so a leg near the range's edge still has its
     * candidates on the other side of it.
     */
    val unclaimedReal: List<UnmatchedRealLeg> = emptyList(),
) {
    /** Stored matches and those that agree to the second. */
    val matched: List<LegMatch> get() = matches.filter { it.settled }

    /** Matches found only by the later passes, for a person to accept. */
    val nearMatches: List<LegMatch> get() = matches.filterNot { it.settled }
}

/** How far apart a fuzzy match may be: sources often stamp a movement at a different point of its life. */
val DEFAULT_FUZZY_WINDOW: Duration = 24.hours

/**
 * How far from a source leg the real legs it was split into may lie: an exchange books each fill of an order, or each
 * payout of a batch, as its own transaction, while the source books one leg for the lot, stamped at one point of it
 * (Koinly's daily Binance commission is the first payout; the rest follow over the next quarter hour).
 */
val DEFAULT_SPLIT_WINDOW: Duration = 15.minutes

/** The most search steps spent on one source leg; there is no limit on how many legs it may be split into. */
private const val MAX_SPLIT_STEPS = 200_000

/** Named steps of [reconcile], in the order they run, as reported to its progress sink. */
private val STEPS =
    listOf(
        "Applying stored matches",
        "Matching legs at the same second",
        "Matching the nearest legs",
        "Summing the legs of each movement",
        "Summing legs split across transactions",
        "Summing the fees of matched trades",
        "Collecting what is left",
    )

/**
 * Matches a reconciliation source's legs against the real legs of the accounts linked to its wallets.
 *
 * A person's stored matches come first: legs whose transactions share a "reconciliation-match" relationship
 * (see [ReconciliationLeg.matchIds]) pair up within a link group whatever their times and amounts. A stored match
 * left with legs on one side only (its partner was deleted) is ignored, so those legs are matched as usual.
 *
 * Legs are compared per **link group** — a wallet (shadow account) plus every real account linked to it
 * — so a source that books one "Binance" wallet can be compared against several real Binance accounts. A
 * pair matches on (group, asset, signed amount), first at the same second (exact), then nearest within
 * [fuzzyWindow]; each leg is used once, so repeated identical movements pair up one-to-one. A final pass
 * sums the leftover legs of each movement (see [ReconciliationLeg.movementKey]) per group and asset — how
 * a trade or withdrawal whose fee one side books separately and the other folds into the gross amount
 * still reconciles.
 *
 * Out of scope: legs on unlinked shadow accounts (counterparties, fees, wallets still to be linked), real
 * legs outside the source's date range, excluded legs on either side (already-reconciled duplicates,
 * rows the source marks deleted), and
 * transfers internal to one group on either side (e.g. Binance → Binance Earn, which a single-wallet
 * source never sees).
 *
 * @param sourceLegs every leg on the source's shadow accounts
 * @param links the source's shadow→real links
 * @param realLegs every leg on the linked real accounts
 */
fun reconcile(
    sourceLegs: List<ReconciliationLeg>,
    links: List<ReconciliationLink>,
    realLegs: List<ReconciliationLeg>,
    fuzzyWindow: Duration = DEFAULT_FUZZY_WINDOW,
    splitWindow: Duration = DEFAULT_SPLIT_WINDOW,
    onProgress: ((Progress) -> Unit)? = null,
): ReconciliationResult {
    var step = 0

    fun report(inner: Float = 0f) {
        val done = step.coerceAtMost(STEPS.size)
        onProgress?.invoke(
            Progress(
                detail = STEPS[step.coerceAtMost(STEPS.lastIndex)],
                fraction = ((done + inner.coerceIn(0f, 1f)) / STEPS.size).coerceIn(0f, 1f),
                processed = done,
                total = STEPS.size,
            ),
        )
    }

    fun nextStep() {
        step++
        report()
    }
    report()
    // Excluded legs (e.g. rows the source deleted) are out of scope, so they don't widen the range either:
    // a real leg only a deleted row covered mustn't be reported as missing from the source.
    val sourceRange =
        sourceLegs
            .filterNot { it.isExcluded }
            .takeIf { it.isNotEmpty() }
            ?.let { legs -> legs.minOf { it.timestamp }..legs.maxOf { it.timestamp } }
    // One source's links: the engine allows a real account one wallet per source, which is what lets
    // each real leg belong to exactly one group.
    require(links.groupBy { it.realAccountId }.values.all { it.size == 1 }) {
        "reconcile() takes one source's links; a real account is linked to several wallets"
    }
    val walletByReal: Map<AccountId, AccountId> = links.associate { it.realAccountId to it.shadowAccountId }
    val linkedWallets: Set<AccountId> = links.mapTo(mutableSetOf()) { it.shadowAccountId }

    fun groupOfSource(accountId: AccountId?): AccountId? = accountId?.takeIf { it in linkedWallets }

    fun groupOfReal(accountId: AccountId?): AccountId? = accountId?.let(walletByReal::get)

    val inScopeSource =
        sourceLegs.filter { leg ->
            val group = groupOfSource(leg.accountId)
            group != null && !leg.isExcluded && groupOfSource(leg.counterpartyAccountId) != group
        }
    val linkedReal =
        realLegs.filter { leg ->
            val group = groupOfReal(leg.accountId)
            group != null && !leg.isExcluded && groupOfReal(leg.counterpartyAccountId) != group
        }
    // Stored matches may pair legs any distance apart, so their real legs are in scope wherever they fall.
    val storedRealIds =
        linkedReal
            .filter { it.matchIds.isNotEmpty() }
            .mapTo(mutableSetOf()) { it.transactionId }
    val inScopeReal =
        linkedReal.filter { leg ->
            sourceRange != null &&
                (
                    leg.transactionId in storedRealIds ||
                        // Widened by the window so a source leg near the range's edge can still find its match.
                        leg.timestamp in (sourceRange.start - fuzzyWindow)..(sourceRange.endInclusive + fuzzyWindow)
                )
        }

    val realAssetsByGroup: Map<AccountId, Set<AssetId>> =
        realLegs
            .mapNotNull { leg -> groupOfReal(leg.accountId)?.let { it to leg.amount.asset.id } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    val realByKey: Map<MatchKey, MutableList<ReconciliationLeg>> =
        inScopeReal.groupByTo(mutableMapOf()) { MatchKey(groupOfReal(it.accountId)!!, it.amount.asset.id, it.amount.amount) }
    val consumed = HashSet<ReconciliationLeg>()
    val matchedSource = HashSet<ReconciliationLeg>()
    val matches = mutableListOf<LegMatch>()

    fun keyOf(leg: ReconciliationLeg) = MatchKey(groupOfSource(leg.accountId)!!, leg.amount.asset.id, leg.amount.amount)

    // Pass 0: a person's stored matches, per (group, match). A transaction between two linked wallets can be in a
    // match on each, so a leg joins only its own group's.
    val storedSource = inScopeSource.flatMap { leg -> leg.matchIds.map { (groupOfSource(leg.accountId)!! to it) to leg } }
    val storedReal = inScopeReal.flatMap { leg -> leg.matchIds.map { (groupOfReal(leg.accountId)!! to it) to leg } }
    val storedRealByKey = storedReal.groupBy({ it.first }, { it.second })
    storedSource
        .groupBy({ it.first }, { it.second })
        .toList()
        .sortedBy { it.first.second }
        .forEach { (key, sourceSide) ->
            val sourceLegs = sourceSide.filter { it !in matchedSource }
            val realLegs = storedRealByKey[key].orEmpty().filter { it !in consumed }
            if (sourceLegs.isEmpty() || realLegs.isEmpty()) return@forEach
            matchedSource += sourceLegs
            consumed += realLegs
            matches += LegMatch(sourceLegs, realLegs, matchId = key.second)
        }

    nextStep()
    // Pass 1: the same second, first come first served (the legs are indistinguishable anyway).
    for (leg in inScopeSource) {
        val best =
            realByKey[keyOf(leg)]
                ?.firstOrNull { it !in consumed && it.timestamp.epochSeconds == leg.timestamp.epochSeconds }
                ?: continue
        consumed += best
        matchedSource += leg
        matches += LegMatch(leg, best)
    }

    nextStep()
    // Pass 2: nearest first across each bucket, so an early leg can't claim a later leg's closer partner.
    inScopeSource
        .filter { it !in matchedSource }
        .groupBy(::keyOf)
        .forEach { (key, legs) ->
            val candidates = realByKey[key]?.filter { it !in consumed } ?: return@forEach
            legs
                .flatMap { leg ->
                    candidates
                        .filter { (it.timestamp - leg.timestamp).absoluteValue <= fuzzyWindow }
                        .map { Triple(leg, it, (it.timestamp - leg.timestamp).absoluteValue) }
                }.sortedBy { it.third }
                .forEach { (leg, real, _) ->
                    if (leg !in matchedSource && real !in consumed) {
                        consumed += real
                        matchedSource += leg
                        matches += LegMatch(leg, real)
                    }
                }
        }

    nextStep()
    // Pass 3: the leftovers of one movement, summed per side (group, asset, movement), nearest first.
    val sourceBundles = bundles(inScopeSource.filter { it !in matchedSource }) { groupOfSource(it.accountId)!! }
    val realBundles = bundles(inScopeReal.filter { it !in consumed }) { groupOfReal(it.accountId)!! }
    val realBundlesByTotal = realBundles.groupBy { it.totalKey }
    val usedRealBundles = HashSet<LegBundle>()
    sourceBundles
        .flatMap { bundle ->
            realBundlesByTotal[bundle.totalKey]
                .orEmpty()
                .filter { (it.timestamp - bundle.timestamp).absoluteValue <= fuzzyWindow }
                .map { Triple(bundle, it, (it.timestamp - bundle.timestamp).absoluteValue) }
        }.sortedBy { it.third }
        .forEach { (sourceBundle, realBundle, _) ->
            if (sourceBundle.legs.none { it in matchedSource } && realBundle !in usedRealBundles) {
                usedRealBundles += realBundle
                consumed += realBundle.legs
                matchedSource += sourceBundle.legs
                matches += LegMatch(sourceBundle.legs, realBundle.legs)
            }
        }

    nextStep()

    // Pass 4: one source leg whose real side is several transactions a moment apart (an order's fills, a batch of
    // dust conversions): leftover real legs, however many, of its group, asset, direction and kind within
    // [splitWindow] that sum to it exactly. Only this direction: several source legs summing to one real leg is
    // what pass 3 allows within a movement, and unrelated source legs must never be summed (see the tests).
    val splitRealByKey =
        inScopeReal
            .filter { it !in consumed }
            .groupBy { SplitKey(groupOfReal(it.accountId)!!, it.amount.asset.id, it.amount.amount.sign(), it.kind) }
    val splitSources = inScopeSource.filter { it !in matchedSource && it.amount.amount.sign() != 0 }
    splitSources.forEachIndexed { index, leg ->
        if (index % PROGRESS_EVERY == 0) report(index.toFloat() / splitSources.size)
        val pool =
            splitRealByKey[SplitKey(groupOfSource(leg.accountId)!!, leg.amount.asset.id, leg.amount.amount.sign(), leg.kind)]
                ?.filter { (it.timestamp - leg.timestamp).absoluteValue <= splitWindow && it !in consumed }
                ?: return@forEachIndexed
        val parts = pool.takeIf { it.size >= 2 }?.let { findSplit(it, leg.amount.amount) } ?: return@forEachIndexed
        consumed += parts
        matchedSource += leg
        matches += LegMatch(listOf(leg), parts)
    }
    nextStep()

    // Pass 5: the fees of a trade both sides already matched, summed per side (group, asset, second). A
    // source can book one fee for an order whose every fill the exchange charged separately (Koinly's
    // single trade fee against Bybit's per-fill tradingFee rows): separate transactions, so pass 3 never
    // bundles them. Only seconds holding a matched trade qualify — two unrelated rewards that happen to
    // share a second are still never summed.
    val matchedTradeSeconds =
        matches
            .flatMap { it.sourceLegs }
            .filter { it.kind == LegTransactionKind.TRADE }
            .mapTo(mutableSetOf()) { groupOfSource(it.accountId)!! to it.timestamp.epochSeconds }

    fun inMatchedTradeSecond(
        leg: ReconciliationLeg,
        group: AccountId,
    ) = (group to leg.timestamp.epochSeconds) in matchedTradeSeconds

    val sourceBySecond =
        secondBundles(
            inScopeSource.filter { it !in matchedSource && inMatchedTradeSecond(it, groupOfSource(it.accountId)!!) },
        ) { groupOfSource(it.accountId)!! }
    val realBySecond =
        secondBundles(
            inScopeReal.filter { it !in consumed && inMatchedTradeSecond(it, groupOfReal(it.accountId)!!) },
        ) { groupOfReal(it.accountId)!! }
            .associateBy { it.totalKey to it.timestamp.epochSeconds }
    for (bundle in sourceBySecond) {
        val real =
            realBySecond[bundle.totalKey to bundle.timestamp.epochSeconds]
                ?.takeIf { candidate -> candidate.legs.none { it in consumed } }
                ?: continue
        consumed += real.legs
        matchedSource += bundle.legs
        matches += LegMatch(bundle.legs, real.legs)
    }

    step = STEPS.size
    report()

    val ignored =
        sourceLegs
            .filter { leg ->
                val group = groupOfSource(leg.accountId)
                group != null && leg.isIgnored && groupOfSource(leg.counterpartyAccountId) != group
            }.map { UnmatchedSourceLeg(it, groupOfSource(it.accountId)!!, assetUnknownToMm = false) }

    return ReconciliationResult(
        sourceRange = sourceRange,
        matches = matches.sortedBy { it.sourceLeg.timestamp },
        missingInMm =
            inScopeSource.filter { it !in matchedSource }.map { leg ->
                val group = groupOfSource(leg.accountId)!!
                UnmatchedSourceLeg(
                    leg = leg,
                    walletAccountId = group,
                    assetUnknownToMm = leg.amount.asset.id !in realAssetsByGroup[group].orEmpty(),
                )
            },
        missingInSource =
            inScopeReal
                .filter { it !in consumed && it.timestamp in sourceRange!! }
                .map { UnmatchedRealLeg(it, groupOfReal(it.accountId)!!) },
        ignored = ignored,
        unclaimedReal = linkedReal.filter { it !in consumed }.map { UnmatchedRealLeg(it, groupOfReal(it.accountId)!!) },
    )
}

/** The legs of one side of one movement (see [ReconciliationLeg.movementKey]) in one group and asset. */
private data class LegBundle(
    val legs: List<ReconciliationLeg>,
    val totalKey: MatchKey,
) {
    val timestamp: Instant get() = legs.first().timestamp
}

private fun bundles(
    legs: List<ReconciliationLeg>,
    groupOf: (ReconciliationLeg) -> AccountId,
): List<LegBundle> =
    legs
        .groupBy { Triple(groupOf(it), it.amount.asset.id, it.movementKey) }
        .map { (key, bundleLegs) ->
            LegBundle(bundleLegs, MatchKey(key.first, key.second, bundleLegs.map { it.amount.amount }.reduce { a, b -> a + b }))
        }

private fun secondBundles(
    legs: List<ReconciliationLeg>,
    groupOf: (ReconciliationLeg) -> AccountId,
): List<LegBundle> =
    legs
        .groupBy { Triple(groupOf(it), it.amount.asset.id, it.timestamp.epochSeconds) }
        .map { (key, bundleLegs) ->
            LegBundle(bundleLegs, MatchKey(key.first, key.second, bundleLegs.map { it.amount.amount }.reduce { a, b -> a + b }))
        }

/** Real legs that can be parts of one source leg: same group, asset, direction and kind. */
private data class SplitKey(
    val group: AccountId,
    val assetId: AssetId,
    val sign: Int,
    val kind: LegTransactionKind,
)

private const val PROGRESS_EVERY = 200

private fun BigInteger.sign(): Int = compareTo(BigInteger.ZERO)

/**
 * Some subset of [pool] (two legs or more) whose amounts sum to [target], if any. Every leg has the target's sign,
 * so the search runs largest first and prunes a branch as soon as a leg overshoots what remains or what is left can
 * no longer reach it. The whole pool is tried first, as the source usually folds everything in the window into one
 * leg. The number of legs is unbounded; only [MAX_SPLIT_STEPS] bounds the work, so a pathological pool can't hang
 * the screen: past it the leg is left for a person to match.
 */
private fun findSplit(
    pool: List<ReconciliationLeg>,
    target: BigInteger,
): List<ReconciliationLeg>? {
    val sorted = pool.sortedByDescending { it.amount.amount.abs() }
    val amounts = sorted.map { it.amount.amount.abs() }
    val goal = target.abs()
    // suffix[i] = what legs i.. add up to, the most they could contribute.
    val suffix = ArrayList<BigInteger>(amounts.size + 1)
    var running = BigInteger.ZERO
    for (i in amounts.indices.reversed()) {
        running += amounts[i]
        suffix += running
    }
    suffix.reverse()
    suffix += BigInteger.ZERO
    if (suffix[0] == goal) return sorted
    val chosen = ArrayList<ReconciliationLeg>()
    var steps = 0

    fun search(
        from: Int,
        remaining: BigInteger,
    ): Boolean {
        if (remaining.sign() == 0) return chosen.size >= 2
        if (from >= amounts.size || steps++ > MAX_SPLIT_STEPS || suffix[from] < remaining) return false
        if (amounts[from] <= remaining) {
            chosen += sorted[from]
            if (search(from + 1, remaining - amounts[from])) return true
            chosen.removeAt(chosen.lastIndex)
        }
        return search(from + 1, remaining)
    }
    return if (search(0, goal)) chosen.toList() else null
}

private data class MatchKey(
    val group: AccountId,
    val assetId: AssetId,
    val signedAmount: BigInteger,
)
