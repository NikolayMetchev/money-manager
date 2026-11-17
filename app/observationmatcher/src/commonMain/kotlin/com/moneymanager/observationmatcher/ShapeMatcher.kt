package com.moneymanager.observationmatcher

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedValues
import kotlin.time.Duration

/** Whether two observations are close enough in time to be records of one movement. Must be symmetric. */
fun interface ShapeWindow {
    fun holds(
        a: MatchObservation,
        b: MatchObservation,
    ): Boolean

    companion object {
        /** Clock time: at most [limit] apart. */
        fun elapsed(limit: Duration): ShapeWindow =
            ShapeWindow { a, b ->
                (a.transfer().timestamp - b.transfer().timestamp).absoluteValue <=
                    limit
            }
    }
}

/** What [ShapeMatcher.match] decided: its [links], and every observation they hold ([involved]). */
data class ShapeMatchResult(
    val links: List<ExclusionLink>,
) {
    val involved: Set<ObservationRef> get() = links.flatMapTo(mutableSetOf()) { it.matched + it.excluded }

    /** Every observation a link excludes. */
    val excluded: Set<ObservationRef> get() = links.flatMapTo(mutableSetOf()) { it.excluded }
}

/**
 * The matches where two records of one movement disagree on shape, decided over a whole scope at once and recorded as
 * [ExclusionLink]s rather than as one transaction: the records stay apart, and the link says which are excluded.
 *
 * `ImportDeduper` found these row by row as each import ran (`classifyAsConduitStatement`, `fundingLeg`, `grossTwin`,
 * `itemisedTwin`, `reconcileSplitConduitMovements`), with the incoming row deciding which rule applied, so the other
 * import order could pair differently or not at all, and the import wrote `reconciled` exclusions onto another
 * source's transfers. Here each kind of match is one symmetric rule over observations, whichever was imported first:
 *
 * - [ShapeReason.CONDUIT_STATEMENT]: a placeholder leg on a conduit (one end a placeholder) against a chain's movement
 *   through that conduit, same asset, amount and direction. The placeholder is excluded; the chain's legs past the
 *   conduit are superseded. The chain's own funding must be identified: a chain funded by a placeholder (a Curve row)
 *   is a conduit's own record, never a card's guessed route.
 * - [ShapeReason.GROSS_ITEMISED]: one leg against another source's net leg plus its fee, through the same account and
 *   direction, that change the account's balance exactly as the one leg does. The gross leg is excluded (and its own
 *   fee, if any).
 * - [ShapeReason.SPLIT_CONDUIT]: a placeholder leg on a conduit against two to [MAX_SPLIT_LEGS] chain movements
 *   through it to one merchant whose amounts (charges plus, refunds minus) sum to it. As a conduit statement match,
 *   once per movement.
 * - [ShapeReason.FEE_ROW]: a record's fee against another container's leg of its own between the same accounts, same asset
 *   and amount. The leg is excluded.
 *
 * Every candidate is found first, then they are taken best first: by kind (in the order above), then the time
 * between the records, then the records' keys. An observation is in at most one link. Nothing depends on the order
 * the observations are given in, their ids, or the order files were imported in. Observations a source excluded
 * itself never take part: they record no counted movement.
 *
 * Pure and DB-free. The caller hands the [ObservationMatcher] what this leaves out ([ShapeMatchResult.involved]
 * observations are not twins of anything) and writes the links' changes ([exclusionLinkChanges]).
 */
class ShapeMatcher(
    private val window: ShapeWindow,
) {
    fun match(scope: Collection<MatchObservation>): ShapeMatchResult {
        val transfers = scope.filter { it.content.values is ObservedValues.Transfer && !it.excluded }.sortedWith(ORDER)
        require(transfers.map { it.ref }.toSet().size == transfers.size) { "an observation appears twice in the scope" }
        val byOwner = transfers.groupBy { it.ref.owner }
        val chains = byOwner.values.flatMap(::chainMovements)
        val placeholders = transfers.mapNotNull(::placeholderMovement)

        val candidates =
            (
                conduitStatements(placeholders, chains, byOwner) + grossItemised(transfers, byOwner) +
                    splitConduits(placeholders, chains, byOwner) + feeRows(transfers, byOwner)
            ).sortedWith(compareBy<Candidate> { it.reason.ordinal }.thenBy { it.distance }.thenBy { it.tieKey })

        val claimed = mutableSetOf<ObservationRef>()
        val links = mutableListOf<ExclusionLink>()
        for (candidate in candidates) {
            val link = candidate.link
            val members = link.matched + link.excluded
            if (members.any { it in claimed }) continue
            claimed += members
            links += link
        }
        return ShapeMatchResult(links)
    }

    /** A placeholder leg: its [conduit] is the end that isn't the placeholder, and money flows into it when [inflow]. */
    private class PlaceholderMovement(
        val observation: MatchObservation,
        val conduit: AccountId,
        val inflow: Boolean,
    )

    /**
     * A chain's movement into (or, for a refund, out of) its [conduit]: [observation] is the chain's leg that reaches
     * the conduit, and [onward] the chain's legs past it, ending at [merchant].
     */
    private class ChainMovement(
        val observation: MatchObservation,
        val conduit: AccountId,
        val inflow: Boolean,
        val onward: List<MatchObservation>,
        val merchant: AccountId,
    )

    private class Candidate(
        val reason: ShapeReason,
        val distance: Long,
        val link: ExclusionLink,
        scope: List<MatchObservation>,
    ) {
        /** The records' keys, order-free: the last word between equally good candidates. */
        val tieKey: String = scope.map { "${it.recordKey}\u0000${it.ref.slot}" }.sorted().joinToString("\u0001")
    }

    private fun placeholderMovement(observation: MatchObservation): PlaceholderMovement? {
        val ends = observation.content.facts.placeholderEnds
        if (ends.size != 1) return null
        val values = observation.transfer()
        return when (ends.single()) {
            ObservationEnd.SOURCE -> PlaceholderMovement(observation, values.targetAccountId, inflow = true)
            ObservationEnd.TARGET -> PlaceholderMovement(observation, values.sourceAccountId, inflow = false)
        }
    }

    /**
     * One owner's pass-through chain (`main`, then `conduit/0`, `conduit/1`, …, each leg sharing an account with the
     * next) as its movements into each conduit. A chain whose funding end is a placeholder is a conduit's own record
     * of a payment, so it has none.
     */
    private fun chainMovements(observations: List<MatchObservation>): List<ChainMovement> {
        val main = observations.firstOrNull { it.ref.slot == MAIN } ?: return emptyList()
        if (main.content.facts.placeholderEnds
                .isNotEmpty()
        ) {
            return emptyList()
        }
        val spend =
            observations
                .mapNotNull { o -> conduitIndex(o.ref.slot)?.let { it to o } }
                .sortedBy { it.first }
                .map { it.second }
        if (spend.isEmpty()) return emptyList()
        val legs = listOf(main) + spend
        return legs.indices.drop(1).mapNotNull { next ->
            val leg = legs[next - 1].transfer()
            val onward = legs.subList(next, legs.size)
            val first = onward.first().transfer()
            val (conduit, inflow) =
                when {
                    first.touches(leg.targetAccountId) -> leg.targetAccountId to true
                    first.touches(leg.sourceAccountId) -> leg.sourceAccountId to false
                    else -> return@mapNotNull null
                }
            val last = onward.last().transfer()
            ChainMovement(legs[next - 1], conduit, inflow, onward, merchant = if (inflow) last.targetAccountId else last.sourceAccountId)
        }
    }

    private fun conduitStatements(
        placeholders: List<PlaceholderMovement>,
        chains: List<ChainMovement>,
        byOwner: Map<ObservationOwner, List<MatchObservation>>,
    ): List<Candidate> {
        val byFlow = chains.groupBy { flowKey(it.conduit, it.inflow, it.observation) }
        return placeholders.flatMap { p ->
            byFlow[flowKey(p.conduit, p.inflow, p.observation)].orEmpty().mapNotNull { chain ->
                if (!apart(p.observation, chain.observation) || !window.holds(p.observation, chain.observation)) return@mapNotNull null
                val excluded = withFee(p.observation, byOwner) + chain.onward.map { it.ref }
                Candidate(
                    ShapeReason.CONDUIT_STATEMENT,
                    secondsApart(p.observation, chain.observation),
                    ExclusionLink(ShapeReason.CONDUIT_STATEMENT, setOf(p.observation.ref, chain.observation.ref), excluded),
                    listOf(p.observation, chain.observation),
                )
            }
        }
    }

    private fun splitConduits(
        placeholders: List<PlaceholderMovement>,
        chains: List<ChainMovement>,
        byOwner: Map<ObservationOwner, List<MatchObservation>>,
    ): List<Candidate> {
        val byConduit = chains.groupBy { it.conduit to it.observation.transfer().assetId }
        return placeholders.flatMap { p ->
            val values = p.observation.transfer()
            byConduit[p.conduit to values.assetId]
                .orEmpty()
                .filter { apart(p.observation, it.observation) && window.holds(p.observation, it.observation) }
                .groupBy { it.merchant }
                .values
                .flatMap { group ->
                    val nearest =
                        group
                            .sortedWith(
                                compareBy<ChainMovement> {
                                    secondsApart(
                                        p.observation,
                                        it.observation,
                                    )
                                }.thenBy { it.observation.recordKey },
                            ).take(MAX_SPLIT_CANDIDATES)
                    subsetsOf(nearest)
                        .filter { set ->
                            set.fold(BigInteger.ZERO) { sum, chain ->
                                val amount = chain.observation.transfer().amount
                                if (chain.inflow == p.inflow) sum + amount else sum - amount
                            } == values.amount
                        }.map { set ->
                            val matched = setOf(p.observation.ref) + set.map { it.observation.ref }
                            val excluded = withFee(p.observation, byOwner) + set.flatMap { chain -> chain.onward.map { it.ref } }
                            Candidate(
                                ShapeReason.SPLIT_CONDUIT,
                                set.maxOf { secondsApart(p.observation, it.observation) },
                                ExclusionLink(ShapeReason.SPLIT_CONDUIT, matched, excluded),
                                listOf(p.observation) + set.map { it.observation },
                            )
                        }
                }
        }
    }

    /**
     * Every net leg with its fee against a single leg of another container through the same account, same direction,
     * that moves the account's balance by exactly as much. The single leg's far end is a placeholder or the net leg's.
     */
    private fun grossItemised(
        transfers: List<MatchObservation>,
        byOwner: Map<ObservationOwner, List<MatchObservation>>,
    ): List<Candidate> {
        val singles =
            transfers
                .filter { it.ref.slot == MAIN && byOwner.getValue(it.ref.owner).none { o -> o.ref.slot == FEE } }
                .groupBy { it.transfer().assetId }
        return byOwner.values.flatMap { observations ->
            val net = observations.firstOrNull { it.ref.slot == MAIN } ?: return@flatMap emptyList()
            val fee = observations.firstOrNull { it.ref.slot == FEE } ?: return@flatMap emptyList()
            val n = net.transfer()
            val f = fee.transfer()
            if (n.assetId != f.assetId) return@flatMap emptyList()
            // The fee is charged to the account the net leg moves money through.
            val owned = f.sourceAccountId
            val inflow =
                when (owned) {
                    n.targetAccountId -> true
                    n.sourceAccountId -> false
                    else -> return@flatMap emptyList()
                }
            val effect = (if (inflow) n.amount else -n.amount) - f.amount
            val netFar = if (inflow) n.sourceAccountId else n.targetAccountId
            singles[n.assetId].orEmpty().mapNotNull { gross ->
                val g = gross.transfer()
                val grossOwned = if (inflow) g.targetAccountId else g.sourceAccountId
                val grossFar = if (inflow) g.sourceAccountId else g.targetAccountId
                val farEnd = if (inflow) ObservationEnd.SOURCE else ObservationEnd.TARGET
                val matches =
                    grossOwned == owned &&
                        (if (inflow) g.amount else -g.amount) == effect &&
                        (grossFar == netFar || farEnd in gross.content.facts.placeholderEnds) &&
                        gross.content.facts.placeholderEnds
                            .none { it != farEnd } &&
                        apart(gross, net) &&
                        window.holds(gross, net)
                if (!matches) return@mapNotNull null
                Candidate(
                    ShapeReason.GROSS_ITEMISED,
                    secondsApart(gross, net),
                    ExclusionLink(ShapeReason.GROSS_ITEMISED, setOf(gross.ref, net.ref, fee.ref), setOf(gross.ref)),
                    listOf(gross, net, fee),
                )
            }
        }
    }

    /**
     * Every record's fee against a leg of another container that books only it: same accounts, asset and amount, a record
     * of its own with no fee. Such a leg is the fee, booked as a row: it is excluded.
     */
    private fun feeRows(
        transfers: List<MatchObservation>,
        byOwner: Map<ObservationOwner, List<MatchObservation>>,
    ): List<Candidate> {
        val rows =
            transfers
                .filter { o ->
                    o.ref.slot == MAIN &&
                        o.content.facts.placeholderEnds
                            .isEmpty() &&
                        byOwner.getValue(o.ref.owner).none { it.ref.slot == FEE }
                }.groupBy { o -> o.transfer().let { listOf(it.sourceAccountId, it.targetAccountId, it.assetId, it.amount.toString()) } }
        return transfers.filter { it.ref.slot == FEE }.flatMap { fee ->
            val f = fee.transfer()
            rows[listOf(f.sourceAccountId, f.targetAccountId, f.assetId, f.amount.toString())].orEmpty().mapNotNull { row ->
                if (!apart(row, fee) || !window.holds(row, fee)) return@mapNotNull null
                Candidate(
                    ShapeReason.FEE_ROW,
                    secondsApart(row, fee),
                    ExclusionLink(ShapeReason.FEE_ROW, setOf(row.ref, fee.ref), setOf(row.ref)),
                    listOf(row, fee),
                )
            }
        }
    }

    /** [observation] with its own fee: an excluded record's fee is part of what it recorded. */
    private fun withFee(
        observation: MatchObservation,
        byOwner: Map<ObservationOwner, List<MatchObservation>>,
    ): Set<ObservationRef> {
        val fee = ObservationRef(observation.ref.owner, FEE)
        return if (byOwner[observation.ref.owner].orEmpty().any { it.ref == fee }) setOf(observation.ref, fee) else setOf(observation.ref)
    }

    private fun flowKey(
        conduit: AccountId,
        inflow: Boolean,
        observation: MatchObservation,
    ): Any {
        val values = observation.transfer()
        return listOf(conduit, inflow, values.assetId, values.amount.toString())
    }

    /** Records of one container are never two records of one movement. */
    private fun apart(
        a: MatchObservation,
        b: MatchObservation,
    ): Boolean = a.container != b.container && a.ref.owner != b.ref.owner

    companion object {
        /** The most chain movements one conduit statement leg splits into. */
        const val MAX_SPLIT_LEGS = 4

        /** How many of a merchant's nearest chain movements a split looks at. */
        const val MAX_SPLIT_CANDIDATES = 8

        // The slots `ObservationSlot` (app/importengineapi, #1422) names.
        private const val MAIN = "main"
        private const val FEE = "fee"
        private const val CONDUIT_PREFIX = "conduit/"

        private fun conduitIndex(slot: String): Int? =
            if (slot.startsWith(CONDUIT_PREFIX)) slot.removePrefix(CONDUIT_PREFIX).toIntOrNull() else null

        private val ORDER: Comparator<MatchObservation> = compareBy<MatchObservation> { it.recordKey }.thenBy { it.ref.slot }

        /** Every subset of [items] with 2 to [MAX_SPLIT_LEGS] members. */
        private fun <T> subsetsOf(items: List<T>): List<List<T>> =
            (1 until (1 shl items.size))
                .map { mask -> items.filterIndexed { i, _ -> mask and (1 shl i) != 0 } }
                .filter { it.size in 2..MAX_SPLIT_LEGS }
    }
}

private fun MatchObservation.transfer(): ObservedValues.Transfer = content.values as ObservedValues.Transfer

private fun ObservedValues.Transfer.touches(account: AccountId): Boolean = sourceAccountId == account || targetAccountId == account

private fun secondsApart(
    a: MatchObservation,
    b: MatchObservation,
): Long = (a.transfer().timestamp - b.transfer().timestamp).absoluteValue.inWholeSeconds
