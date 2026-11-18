package com.moneymanager.observationmatcher

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.calendar.BusinessDayCalendar
import com.moneymanager.domain.model.calendar.WeekendOnlyCalendar
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.meets
import com.moneymanager.importengineapi.AccountBridge
import com.moneymanager.importengineapi.DedupePolicy
import kotlin.time.Instant

/**
 * What one family's [DedupePolicy] lets the cross-source rules do. A window is null when the policy doesn't turn its
 * rule on: every cross-source rule needs the policy's reconciliation exclusion and relationship types, and the
 * placeholder rules also its unidentified-counterparty type, as `ImportDeduper` required.
 *
 * @property reconcileWindow how far apart two records of the same accounts may be.
 * @property placeholderWindow how far apart a record with a placeholder end and its twin may be.
 * @property preciseTwin the family's precise records take an approximately-dated twin (`ImportDeduper`'s approximateTwin).
 * @property approximateTwin the family's approximately-dated records take a precise twin (its preciseTwin).
 * @property bridges the family's internal-transfer bridges, with [bridgeWindow] and [bridgeTolerancePercent].
 */
data class CrossSourcePolicy(
    val reconcileWindow: SettlementWindow? = null,
    val placeholderWindow: SettlementWindow? = null,
    val preciseTwin: Boolean = false,
    val approximateTwin: Boolean = false,
    val bridges: List<AccountBridge> = emptyList(),
    val bridgeWindow: SettlementWindow? = null,
    val bridgeTolerancePercent: BigDecimal = BigDecimal.ZERO,
) {
    companion object {
        fun of(policy: DedupePolicy): CrossSourcePolicy =
            when (policy) {
                is DedupePolicy.None -> {
                    CrossSourcePolicy()
                }

                is DedupePolicy.UniqueIdentifier -> {
                    val reconciles = policy.reconciledExclusionAttributeTypeId != null && policy.reconciledRelationshipTypeId != null
                    CrossSourcePolicy(
                        reconcileWindow = policy.reconcileWindow.takeIf { reconciles },
                        placeholderWindow =
                            policy.unidentifiedCounterpartyWindow.takeIf {
                                reconciles && policy.unidentifiedCounterpartyAttributeTypeId != null
                            },
                    )
                }

                is DedupePolicy.FuzzyAllFields -> {
                    val reconciles = policy.reconciledExclusionAttributeTypeId != null && policy.reconciledRelationshipTypeId != null
                    CrossSourcePolicy(
                        reconcileWindow = policy.reconcileWindow.takeIf { reconciles },
                        placeholderWindow =
                            (policy.unidentifiedCounterpartyWindow ?: SettlementWindow.Elapsed(policy.dateTolerance.inWholeSeconds))
                                .takeIf { reconciles && policy.unidentifiedCounterpartyAttributeTypeId != null },
                        preciseTwin = true,
                    )
                }

                is DedupePolicy.ApiMultiKey -> {
                    val reconciles = policy.reconciledExclusionAttributeTypeId != null && policy.reconciledRelationshipTypeId != null
                    CrossSourcePolicy(
                        reconcileWindow = policy.reconcileWindow.takeIf { reconciles },
                        placeholderWindow =
                            policy.unidentifiedCounterpartyWindow.takeIf {
                                reconciles && policy.unidentifiedCounterpartyAttributeTypeId != null
                            },
                        preciseTwin = true,
                        approximateTwin = true,
                        bridges = if (reconciles && policy.internalTransferWindow != null) policy.internalTransferBridges else emptyList(),
                        bridgeWindow = policy.internalTransferWindow,
                        bridgeTolerancePercent = policy.internalTransferAmountTolerance,
                    )
                }
            }
    }
}

/**
 * The rules that make two sources' records of one movement one transaction: `ImportDeduper`'s cross-source
 * reconciliation, as pair rules. Where the deduper kept one record counted and excluded the other, both are now
 * observations of one transaction, and the materializer takes each field from the record that knows it best.
 *
 * Each rule is asked both ways round, each record in the incoming role under its own family's policy, so a pair
 * found in one import order is found in the other. Every rule pairs only:
 * - records of two families (a source never reconciles with itself: that is [SameFamilyRules]), or one family's
 *   statements of two accounts ([SameAccountsRule]),
 * - of two containers,
 * - main transfers (never a fee or another derived slot),
 * - that both count (a record its source excluded stands for no movement),
 * - and neither of them a reconciliation source's shadow record.
 *
 * Complete linkage in the matcher makes each match one to one: a record already matched joins another only if it
 * pairs with every record of that transaction.
 */
object CrossSourceRules {
    /**
     * The cross-source rules, in rank order, for the families in [policies].
     *
     * @param containerAccounts the accounts each container's own records book (a statement's own account): a record
     *   whose counterparty is one of them is a transfer between two of that source's accounts, never a twin of its record.
     * @param calendarFor the business days of the accounts a pair touches, for business-day windows.
     */
    fun forPolicies(
        policies: Map<String, DedupePolicy>,
        containerAccounts: Map<String, Set<AccountId>> = emptyMap(),
        calendarFor: (List<AccountId>) -> BusinessDayCalendar = { WeekendOnlyCalendar },
    ): List<PairRule> {
        val context = Context(policies.mapValues { CrossSourcePolicy.of(it.value) }, containerAccounts, calendarFor)
        return listOf(
            ApproximateTwinRule(context),
            SameAccountsRule(context),
            BridgeRule(context),
            UnidentifiedTwinRule(context),
            PlaceholderTwinRule(context),
            OtherSideRule(context),
        )
    }
}

internal class Context(
    val policies: Map<String, CrossSourcePolicy>,
    private val containerAccounts: Map<String, Set<AccountId>>,
    private val calendarFor: (List<AccountId>) -> BusinessDayCalendar,
) {
    fun policy(observation: MatchObservation): CrossSourcePolicy = policies[observation.family] ?: NONE

    fun booksAccount(
        observation: MatchObservation,
        account: AccountId,
    ): Boolean = account in containerAccounts[observation.container].orEmpty()

    /** The transfer the cross-source rules may read, or null when [observation] is never a cross-source twin. */
    fun eligible(observation: MatchObservation): ObservedValues.Transfer? {
        if (observation.family !in policies || observation.ref.slot != MAIN_SLOT) return null
        if (observation.excluded || observation.crossSource.shadow) return null
        return observation.content.values as? ObservedValues.Transfer
    }

    /**
     * Seconds between [a] and [b] when [window] has them meet (0 when an approximate record's span covers the other's
     * time), else null.
     */
    fun within(
        window: SettlementWindow?,
        a: MatchObservation,
        b: MatchObservation,
    ): Long? {
        window ?: return null
        val x = a.content.values as ObservedValues.Transfer
        val y = b.content.values as ObservedValues.Transfer
        val untilA = a.crossSource.approximateUntil
        val untilB = b.crossSource.approximateUntil
        val calendar = calendarFor(listOf(x.sourceAccountId, x.targetAccountId, y.sourceAccountId, y.targetAccountId).distinct())
        if (!window.meets(x.timestamp, untilA, y.timestamp, untilB, calendar)) return null
        return spanGap(x.timestamp, untilA, y.timestamp, untilB)
    }

    companion object {
        const val MAIN_SLOT = "main"
        private val NONE = CrossSourcePolicy()
    }
}

/** Seconds between two time spans (a precise record's span is its instant), 0 when they overlap. */
private fun spanGap(
    start: Instant,
    until: Instant?,
    otherStart: Instant,
    otherUntil: Instant?,
): Long {
    val end = maxOf(start, until ?: start)
    val otherEnd = maxOf(otherStart, otherUntil ?: otherStart)
    return when {
        end < otherStart -> (otherStart - end).inWholeSeconds
        otherEnd < start -> (start - otherEnd).inWholeSeconds
        else -> 0
    }
}

/** The smallest non-null of [a] and [b]. */
private fun nearest(
    a: Long?,
    b: Long?,
): Long? = listOfNotNull(a, b).minOrNull()

private fun ObservedValues.Transfer.at(end: ObservationEnd): AccountId =
    when (end) {
        ObservationEnd.SOURCE -> sourceAccountId
        ObservationEnd.TARGET -> targetAccountId
    }

private fun ObservationEnd.other(): ObservationEnd =
    when (this) {
        ObservationEnd.SOURCE -> ObservationEnd.TARGET
        ObservationEnd.TARGET -> ObservationEnd.SOURCE
    }

/** The one placeholder end of [observation], or null when it has none or two. */
private fun placeholderEnd(observation: MatchObservation): ObservationEnd? =
    observation.content.facts.placeholderEnds
        .singleOrNull()

/** Base for the rules that compare records of one asset and amount, from two families. */
internal abstract class CrossSourceRule(
    protected val context: Context,
) : PairRule {
    override val joinsSameContainer = false

    override fun bucket(observation: MatchObservation): Any? {
        val values = context.eligible(observation) ?: return null
        return listOf(values.assetId, values.amount.toString())
    }

    final override fun distance(
        a: MatchObservation,
        b: MatchObservation,
    ): Long? {
        if (a.family == b.family && !joinsOwnStatements(a, b)) return null
        return nearest(oneWay(a, b), oneWay(b, a))
    }

    /** Whether this rule pairs two records of one family, because each is the statement of its own account. */
    protected open fun joinsOwnStatements(
        a: MatchObservation,
        b: MatchObservation,
    ): Boolean = false

    /** The distance when [incoming], under its own family's policy, pairs with [existing]. */
    protected abstract fun oneWay(
        incoming: MatchObservation,
        existing: MatchObservation,
    ): Long?
}

/**
 * One source dates the movement only approximately (a statement's date, a day's span) and the other to the second:
 * the same amount between the same accounts, or with a placeholder at one end, through the same own account. The
 * precise record's time wins in the merge. `ImportDeduper`'s approximateTwin (the precise record's family takes it)
 * and preciseTwin (the approximate record's family does, matching on one shared flow).
 */
internal class ApproximateTwinRule(
    context: Context,
) : CrossSourceRule(context) {
    override val name = "approximate twin"

    override fun oneWay(
        incoming: MatchObservation,
        existing: MatchObservation,
    ): Long? {
        val x = incoming.content.values as ObservedValues.Transfer
        val y = existing.content.values as ObservedValues.Transfer
        val incomingApproximate = incoming.isApproximate()
        if (incomingApproximate == existing.isApproximate()) return null
        val policy = context.policy(incoming)
        val accountsMeet =
            if (incomingApproximate) {
                policy.approximateTwin && (x.targetAccountId == y.targetAccountId || x.sourceAccountId == y.sourceAccountId)
            } else {
                val placeholder = placeholderEnd(incoming)
                policy.preciseTwin &&
                    (
                        (x.sourceAccountId == y.sourceAccountId && x.targetAccountId == y.targetAccountId) ||
                            (placeholder != null && x.at(placeholder.other()) == y.at(placeholder.other()))
                    )
            }
        return if (accountsMeet) context.within(policy.reconcileWindow, incoming, existing) else null
    }

    private fun MatchObservation.isApproximate(): Boolean = content.facts.approximateTime || crossSource.approximateUntil != null
}

/**
 * Two sources' records of the same amount from the same account to the same account (`ImportDeduper`'s sameAccounts).
 * One strategy's exports of two accounts are two sources here: a transfer between them is on both statements, one
 * at each end (Monzo's personal and joint accounts).
 */
internal class SameAccountsRule(
    context: Context,
) : CrossSourceRule(context) {
    override val name = "same accounts"

    override fun joinsOwnStatements(
        a: MatchObservation,
        b: MatchObservation,
    ): Boolean {
        val endA = a.content.facts.ownEnd ?: return false
        val endB = b.content.facts.ownEnd ?: return false
        // Two statements of one account (a PDF and a QIF export, each with its own references) carry the same line:
        // the same time, accounts and amount. A line only one of them has at that time is another movement.
        return endA != endB ||
            (a.content.values as ObservedValues.Transfer).timestamp == (b.content.values as ObservedValues.Transfer).timestamp
    }

    override fun bucket(observation: MatchObservation): Any? {
        val values = context.eligible(observation) ?: return null
        return listOf(values.assetId, values.amount.toString(), values.sourceAccountId, values.targetAccountId)
    }

    override fun oneWay(
        incoming: MatchObservation,
        existing: MatchObservation,
    ): Long? = context.within(context.policy(incoming).reconcileWindow, incoming, existing)
}

/**
 * An internal transfer between two of the person's own accounts, recorded at each end: the exchange's record moves into
 * (or out of) the bridge's exchange account against an outside address, the other source's moves out of (or into) the
 * bridge's app account. Amounts may differ by the bridge's tolerance (a network fee). The merge takes each end from the
 * record whose own account it is: app to exchange.
 */
internal class BridgeRule(
    context: Context,
) : CrossSourceRule(context) {
    override val name = "internal-transfer bridge"

    override fun bucket(observation: MatchObservation): Any? {
        val values = context.eligible(observation) ?: return null
        if (context.policies.values.none { it.bridges.isNotEmpty() }) return null
        return values.assetId
    }

    override fun oneWay(
        incoming: MatchObservation,
        existing: MatchObservation,
    ): Long? {
        val x = incoming.content.values as ObservedValues.Transfer
        val y = existing.content.values as ObservedValues.Transfer
        val policy = context.policy(incoming)
        val bridged =
            policy.bridges.any { bridge ->
                (x.targetAccountId == bridge.exchangeAccountId && y.sourceAccountId == bridge.appAccountId) ||
                    (x.sourceAccountId == bridge.exchangeAccountId && y.targetAccountId == bridge.appAccountId)
            }
        if (!bridged || !withinTolerance(x, y, policy.bridgeTolerancePercent)) return null
        return context.within(policy.bridgeWindow, incoming, existing)
    }

    private fun withinTolerance(
        incoming: ObservedValues.Transfer,
        existing: ObservedValues.Transfer,
        tolerancePercent: BigDecimal,
    ): Boolean {
        if (incoming.amount == existing.amount) return true
        if (tolerancePercent <= BigDecimal.ZERO) return false
        val diff = (incoming.amount - existing.amount).abs().toBigDecimal()
        return diff * BigDecimal(PERCENT) <= existing.amount.abs().toBigDecimal() * tolerancePercent
    }

    private companion object {
        const val PERCENT = 100
    }
}

/**
 * One source has only a placeholder for the far end (an account's own export: "GBP Deposit (via FPS)"; a statement
 * saying "FROM <the holder>") and the other names it (a bank resolving the sort code and account number): the same
 * amount moving the same way through the account they share. The named end wins in the merge. `ImportDeduper`'s
 * identifiedTwin and placeholderTwin, which were one rule seen from either import order.
 *
 * The named record's far end must be neither the placeholder itself nor an account the placeholder's own source books
 * (a transfer between two of that source's accounts).
 */
internal class UnidentifiedTwinRule(
    context: Context,
) : CrossSourceRule(context) {
    override val name = "unidentified counterparty"

    override fun oneWay(
        incoming: MatchObservation,
        existing: MatchObservation,
    ): Long? {
        val (placeholder, named) = placeholderAndNamed(incoming, existing) ?: return null
        if (placeholder.crossSource.passThrough || named.crossSource.passThrough) return null
        val end = placeholderEnd(placeholder) ?: return null
        val p = placeholder.content.values as ObservedValues.Transfer
        val n = named.content.values as ObservedValues.Transfer
        val sharesOwnAccount = p.at(end.other()) == n.at(end.other())
        val farEnd = n.at(end)
        if (!sharesOwnAccount || farEnd == p.at(end) || context.booksAccount(placeholder, farEnd)) return null
        return context.within(context.policy(incoming).placeholderWindow, incoming, existing)
    }
}

/**
 * Two statements of one strategy that both stand in a placeholder for the same far end (two American Express
 * statements that each say "PAYMENT RECEIVED"): the same amount moving the same way through the same own account,
 * inside the family's placeholder window, is one payment, whatever reference each statement gives it (a reference on
 * a placeholder doesn't identify the movement). Never two rows of one statement, and never two records that are each
 * already one movement with a record naming the far end: two like payments stay two.
 */
internal class PlaceholderTwinRule(
    private val context: Context,
) : PairRule {
    override val name = "placeholder twin"
    override val joinsSameContainer = false

    override fun bucket(observation: MatchObservation): Any? {
        val values = context.eligible(observation) ?: return null
        if (observation.crossSource.passThrough) return null
        val end = placeholderEnd(observation) ?: return null
        return listOf(observation.family, values.assetId, values.amount.toString(), end, values.at(end.other()))
    }

    override fun distance(
        a: MatchObservation,
        b: MatchObservation,
    ): Long? {
        if (a.family != b.family) return null
        return context.within(context.policy(a).placeholderWindow, a, b)
    }

    override fun joinsGroups(
        x: List<MatchObservation>,
        y: List<MatchObservation>,
    ): Boolean =
        listOf(x, y).any { group ->
            group.all {
                it.content.facts.placeholderEnds
                    .isNotEmpty()
            }
        }
}

/**
 * The two records of one transfer when they share no account: each source knows one real end and has only a stand-in for
 * the other (a broker's `Freetrade Top Ups -> ISA`, a bank's `Monzo -> Freetrade Limited`). Only the asset, amount and
 * time say they are one movement; the merge makes it `Monzo -> ISA`, real end to real end (`ImportDeduper`'s otherSideOf).
 *
 * The named record's end where the placeholder stands must be its own statement account (`ObservationFacts.ownEnd`): a bank line
 * `Santander -> Marcus` is never PayPal's withdrawal to an unknown bank. Ranked last, so it only pairs records no
 * other rule explains; neither record may touch an account the other's source books.
 */
internal class OtherSideRule(
    context: Context,
) : CrossSourceRule(context) {
    override val name = "other side"

    /**
     * A movement two sources already record end to end (a bank line and the savings account's own line) has both its
     * ends: it is nobody's other side (`ImportDeduper` never offered a record another row had reconciled against).
     */
    override fun joinsGroups(
        x: List<MatchObservation>,
        y: List<MatchObservation>,
    ): Boolean =
        listOf(x, y).none { group ->
            // One statement's re-exports are one source: only a second statement attests the far end.
            group
                .filter {
                    it.content.facts.placeholderEnds
                        .isEmpty()
                }.distinctBy { it.family to it.content.facts.ownEnd }
                .size > 1
        }

    override fun oneWay(
        incoming: MatchObservation,
        existing: MatchObservation,
    ): Long? {
        val (placeholder, named) = placeholderAndNamed(incoming, existing) ?: return null
        if (placeholder.crossSource.passThrough || named.crossSource.passThrough) return null
        val end = placeholderEnd(placeholder) ?: return null
        if (named.content.facts.ownEnd != end) return null
        val p = placeholder.content.values as ObservedValues.Transfer
        val n = named.content.values as ObservedValues.Transfer
        val crosses =
            listOf(n.sourceAccountId, n.targetAccountId).any { context.booksAccount(placeholder, it) } ||
                listOf(p.sourceAccountId, p.targetAccountId).any { context.booksAccount(named, it) }
        if (crosses) return null
        return context.within(context.policy(incoming).placeholderWindow, incoming, existing)
    }
}

/** The record with a placeholder end and the one naming both ends, or null unless exactly one of [a] and [b] has one. */
private fun placeholderAndNamed(
    a: MatchObservation,
    b: MatchObservation,
): Pair<MatchObservation, MatchObservation>? {
    val aPlaceholder =
        a.content.facts.placeholderEnds
            .isNotEmpty()
    val bPlaceholder =
        b.content.facts.placeholderEnds
            .isNotEmpty()
    return when {
        aPlaceholder && !bPlaceholder -> a to b
        bPlaceholder && !aPlaceholder -> b to a
        else -> null
    }
}
