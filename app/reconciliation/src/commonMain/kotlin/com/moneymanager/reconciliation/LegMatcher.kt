package com.moneymanager.reconciliation

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Source legs paired with the real legs recording the same movement: usually one each, but several when
 * the two sides split a movement differently (e.g. the source books a trade and its fee as two legs, the
 * real account one gross leg) — then the legs on each side share a timestamp and sum to the same amount.
 */
data class LegMatch(
    val sourceLegs: List<ReconciliationLeg>,
    val realLegs: List<ReconciliationLeg>,
) {
    constructor(sourceLeg: ReconciliationLeg, realLeg: ReconciliationLeg) : this(listOf(sourceLeg), listOf(realLeg))

    val sourceLeg: ReconciliationLeg get() = sourceLegs.first()
    val realLeg: ReconciliationLeg get() = realLegs.first()

    /** Exact = one leg each at the same second; otherwise found by a later pass. */
    val exact: Boolean
        get() = sourceLegs.size == 1 && realLegs.size == 1 && sourceLeg.timestamp.epochSeconds == realLeg.timestamp.epochSeconds

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
) {
    val exactMatches: List<LegMatch> get() = matches.filter { it.exact }
    val fuzzyMatches: List<LegMatch> get() = matches.filterNot { it.exact }
}

/** How far apart a fuzzy match may be: sources often stamp a movement at a different point of its life. */
val DEFAULT_FUZZY_WINDOW: Duration = 24.hours

/**
 * Matches a reconciliation source's legs against the real legs of the accounts linked to its wallets.
 *
 * Legs are compared per **link group** — a wallet (shadow account) plus every real account linked to it
 * — so a source that books one "Binance" wallet can be compared against several real Binance accounts. A
 * pair matches on (group, asset, signed amount), first at the same second (exact), then nearest within
 * [fuzzyWindow]; each leg is used once, so repeated identical movements pair up one-to-one.
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
): ReconciliationResult {
    val sourceRange =
        sourceLegs.takeIf { it.isNotEmpty() }?.let { legs -> legs.minOf { it.timestamp }..legs.maxOf { it.timestamp } }
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
    val inScopeReal =
        realLegs.filter { leg ->
            val group = groupOfReal(leg.accountId)
            group != null &&
                !leg.isExcluded &&
                sourceRange != null &&
                // Widened by the window so a source leg near the range's edge can still find its match.
                leg.timestamp in (sourceRange.start - fuzzyWindow)..(sourceRange.endInclusive + fuzzyWindow) &&
                groupOfReal(leg.counterpartyAccountId) != group
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

    // Pass 3: the leftovers of one movement, summed per side (group, asset, second), nearest first.
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
    )
}

/** The legs of one side of one movement: same group, asset and second. */
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
        .groupBy { Triple(groupOf(it), it.amount.asset.id, it.timestamp.epochSeconds) }
        .map { (key, bundleLegs) ->
            LegBundle(bundleLegs, MatchKey(key.first, key.second, bundleLegs.map { it.amount.amount }.reduce { a, b -> a + b }))
        }

private data class MatchKey(
    val group: AccountId,
    val assetId: AssetId,
    val signedAmount: BigInteger,
)
