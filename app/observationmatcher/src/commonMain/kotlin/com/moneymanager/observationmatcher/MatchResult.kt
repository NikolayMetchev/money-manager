package com.moneymanager.observationmatcher

import com.moneymanager.domain.model.TransactionId

/** The transaction an observation is assigned to. */
sealed interface TransactionTarget {
    /** A transaction that exists. */
    data class Existing(
        val id: TransactionId,
    ) : TransactionTarget

    /**
     * A transaction to create, for the observations assigned to it. [anchor] is the first of them in the matcher's
     * order: every observation with the same anchor goes to the one new transaction.
     */
    data class New(
        val anchor: ObservationRef,
    ) : TransactionTarget
}

/** [ref] moves [from] its transaction (null: it is new) [to] another. */
data class AssignmentChange(
    val ref: ObservationRef,
    val from: TransactionId?,
    val to: TransactionTarget,
)

/** Why [a] and [b] share a transaction: the rule that paired them, or [PIN] when a person did. */
data class Join(
    val a: ObservationRef,
    val b: ObservationRef,
    val rule: String,
) {
    companion object {
        const val PIN = "pin"
    }
}

/**
 * What [ObservationMatcher.match] decided over one scope.
 *
 * @property assignments every observation of the scope and its transaction.
 * @property changes the assignments that differ from the observations' current ones, in the matcher's order.
 * @property released transactions some observation of the scope was assigned to and none is now: nothing backs them.
 * @property joins the pairs that put two observations in one transaction, in the order they were taken.
 * @property exclusions what the matcher's stages leave out of balances, in the order they were found.
 */
data class MatchResult(
    val assignments: Map<ObservationRef, TransactionTarget>,
    val changes: List<AssignmentChange>,
    val released: Set<TransactionId>,
    val joins: List<Join>,
    val exclusions: List<ExclusionLink> = emptyList(),
)
