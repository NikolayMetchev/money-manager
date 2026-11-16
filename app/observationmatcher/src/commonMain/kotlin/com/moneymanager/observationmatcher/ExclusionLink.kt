package com.moneymanager.observationmatcher

/** Which kind of shape-changing match an [ExclusionLink] records. */
enum class ShapeReason {
    /**
     * A conduit's own statement (PayPal's "General Credit Card Deposit", a Curve row naming only its card) records the
     * money a card chain moves into the conduit: the statement's placeholder leg is excluded, and the chain's legs past
     * the conduit, the card statement's guess at what the conduit did with the money, are superseded.
     */
    CONDUIT_STATEMENT,

    /**
     * One source books a movement net plus a separate fee, another as one gross leg (a Binance withdrawal): the gross
     * leg is excluded.
     */
    GROSS_ITEMISED,

    /**
     * One conduit statement leg against two to [ShapeMatcher.MAX_SPLIT_LEGS] chain movements to one merchant that sum
     * to it (Uber authorises £21.58 and tops up £0.27; PayPal records £21.85).
     */
    SPLIT_CONDUIT,
}

/**
 * A match where records of one movement disagree on shape, so they stay separate transactions and some are excluded
 * from balances. The matcher owns it: it is a relationship written by `Source.Reconciler` between the transactions
 * of [matched] and [excluded], recomputed from scratch for the scope on every sync, and it goes away when any of its
 * observations does. Nothing else excludes these observations: the link is the reason.
 *
 * @property matched the observations the match pairs (the placeholder and the chain movement, the gross leg and the
 *   net leg and its fee).
 * @property excluded the observations it takes out of balances: some of [matched] and the legs they supersede. What
 *   is in [matched] and not here stays counted.
 */
data class ExclusionLink(
    val reason: ShapeReason,
    val matched: Set<ObservationRef>,
    val excluded: Set<ObservationRef>,
)

/** What a sync writes for exclusion links: those to [add] and the stored ones to [remove]. */
data class ExclusionLinkChanges(
    val add: List<ExclusionLink>,
    val remove: List<ExclusionLink>,
) {
    val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()
}

/**
 * The changes that turn the [stored] links of a scope into the [computed] ones. A link is its observations and
 * reason, never an id, so recomputing an unchanged scope changes nothing.
 */
fun exclusionLinkChanges(
    stored: Collection<ExclusionLink>,
    computed: Collection<ExclusionLink>,
): ExclusionLinkChanges {
    val storedSet = stored.toSet()
    val computedSet = computed.toSet()
    return ExclusionLinkChanges(
        add = computed.filter { it !in storedSet }.distinct(),
        remove = stored.filter { it !in computedSet }.distinct(),
    )
}
