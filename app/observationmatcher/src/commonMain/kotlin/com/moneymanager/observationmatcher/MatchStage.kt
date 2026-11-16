package com.moneymanager.observationmatcher

/** One transaction of a [MatchResult] as a [MatchStage] sees it: its target and its observations, in matcher order. */
data class MatchedTransaction(
    val target: TransactionTarget,
    val members: List<MatchObservation>,
)

/**
 * A match that is not "these observations are one transaction": two sources record one movement in different shapes
 * (one gross trade and its fills, a conversion booked as transfers and the same conversion as trades), so both
 * transactions stay and one is excluded from balances. A [PairRule] can't say that: it joins two observations into
 * one transaction.
 *
 * Stages run after the pair rules, over the transactions they made, in the order they are given to the
 * [ObservationMatcher]; a transaction one stage links is claimed, and no later stage links it again. A stage must be
 * deterministic: it sees the transactions in the matcher's order and must not depend on ids beyond that.
 */
interface MatchStage {
    /** Shown in [StageExclusion.rule]. */
    val name: String

    /**
     * Keys under which [observation] may take part in this stage: [ObservationMatcher.scopeOf] brings in every stored
     * observation sharing one with an observation in the scope. A superset is fine; a key too broad only widens the
     * scope.
     */
    fun scopeKeys(observation: MatchObservation): Collection<Any>

    /** The matches this stage finds among [transactions], leaving out the [claimed] ones. */
    fun exclusions(
        transactions: List<MatchedTransaction>,
        claimed: Set<TransactionTarget>,
    ): List<StageMatch>
}

/** A [MatchStage]'s match by transaction: the transactions [excluded] record what the transactions [by] do, in another shape. */
data class StageMatch(
    val excluded: List<MatchedTransaction>,
    val by: List<MatchedTransaction>,
) {
    /** Every transaction it names. */
    val targets: List<TransactionTarget> get() = (excluded + by).map { it.target }
}
