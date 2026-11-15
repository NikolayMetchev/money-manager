package com.moneymanager.observationmatcher

import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.StringSimilarity
import kotlin.time.Instant

/**
 * One way two observations can be records of the same movement. The matcher's rule table is an ordered list of
 * these: a rule's place in it is its rank, and a pair found by an earlier rule is taken before any found by a later
 * one.
 */
interface PairRule {
    /** Shown in [MatchResult.joins], to explain why two observations share a transaction. */
    val name: String

    /**
     * Whether the rule may join two observations of one container. A source repeating its own id says the two rows
     * are one movement; two like rows of one statement with no id are two movements.
     */
    val joinsSameContainer: Boolean

    /**
     * The bucket [observation] falls in, or null when the rule never applies to it. Only observations of one bucket
     * are compared, so a bucket should be as narrow as the rule allows (an id, an amount).
     */
    fun bucket(observation: MatchObservation): Any?

    /**
     * How far apart [a] and [b] are, in seconds, when the rule pairs them (0 for an exact match), else null. A
     * nearer pair is taken first. Must be symmetric.
     */
    fun distance(
        a: MatchObservation,
        b: MatchObservation,
    ): Long?
}

/**
 * The rules that make two observations of one strategy (a re-export of a statement, the same API item downloaded in
 * two sessions) one transaction: `ImportDeduper`'s DUPLICATE and UPDATED classifications, as pair rules. Each family's
 * own [DedupePolicy] decides which apply to it; a family without one is never deduplicated.
 */
object SameFamilyRules {
    /** Every same-family rule, in rank order, for the families in [policies]. */
    fun forPolicies(policies: Map<String, DedupePolicy>): List<PairRule> =
        listOf(
            ApiIdRule(policies.familiesWhere { it is DedupePolicy.ApiMultiKey }),
            UniqueKeyRule(policies.familiesWhere { it is DedupePolicy.ApiMultiKey || it is DedupePolicy.UniqueIdentifier }),
            ApiFieldsRule(policies.familiesWhere { it is DedupePolicy.ApiMultiKey }),
            ExactRule(policies.familiesWhere { it is DedupePolicy.FuzzyAllFields }),
            FuzzyRule(policies.mapNotNull { (family, policy) -> (policy as? DedupePolicy.FuzzyAllFields)?.let { family to it } }.toMap()),
        )

    private fun Map<String, DedupePolicy>.familiesWhere(predicate: (DedupePolicy) -> Boolean): Set<String> = filterValues(predicate).keys
}

/** The provider's own transaction id: the same id in one family is the same movement, wherever it was downloaded. */
internal class ApiIdRule(
    private val families: Set<String>,
) : PairRule {
    override val name = "api id"
    override val joinsSameContainer = true

    override fun bucket(observation: MatchObservation): Any? {
        if (observation.family !in families) return null
        val apiId = observation.identity.apiId ?: return null
        return listOf(observation.family, observation.ref.slot, observation.content.values.kind, apiId)
    }

    override fun distance(
        a: MatchObservation,
        b: MatchObservation,
    ): Long = secondsApart(a.content.values, b.content.values)
}

/**
 * The strategy's unique-identifier columns: one key is one movement, whatever else changed (`ImportDeduper`'s UPDATED),
 * and a row repeating an earlier row's key in one file is that row again.
 */
internal class UniqueKeyRule(
    private val families: Set<String>,
) : PairRule {
    override val name = "unique key"
    override val joinsSameContainer = true

    override fun bucket(observation: MatchObservation): Any? {
        if (observation.family !in families) return null
        val key = observation.identity.uniqueKey.takeIf { it.isNotEmpty() } ?: return null
        return listOf(observation.family, observation.ref.slot, observation.content.values.kind, key)
    }

    override fun distance(
        a: MatchObservation,
        b: MatchObservation,
    ): Long = secondsApart(a.content.values, b.content.values)
}

/**
 * An API item without a matching id: the same time, asset and amount between the same two accounts, either way round,
 * unless both carry their own, different ids (Kraken books an Earn allocation as two ledger rows that differ only in
 * direction and id). As in `ImportDeduper`, this also joins two items of one session.
 */
internal class ApiFieldsRule(
    private val families: Set<String>,
) : PairRule {
    override val name = "api fields"
    override val joinsSameContainer = true

    override fun bucket(observation: MatchObservation): Any? {
        if (observation.family !in families) return null
        val values = observation.content.values as? ObservedValues.Transfer ?: return null
        val accounts = setOf(values.sourceAccountId, values.targetAccountId)
        return listOf(observation.family, observation.ref.slot, values.timestamp, values.assetId, values.amount.toString(), accounts)
    }

    override fun distance(
        a: MatchObservation,
        b: MatchObservation,
    ): Long? {
        val idA = a.identity.apiId
        val idB = b.identity.apiId
        return if (idA != null && idB != null && idA != idB) null else 0
    }
}

/**
 * Every core field equal: time, both accounts, asset, amount and description. Attributes may differ (the newer
 * record's win in the rank-merge, as `ImportDeduper`'s UPDATED overwrote them).
 */
internal class ExactRule(
    private val families: Set<String>,
) : PairRule {
    override val name = "exact"
    override val joinsSameContainer = false

    override fun bucket(observation: MatchObservation): Any? {
        if (observation.family !in families) return null
        val values = observation.content.values as? ObservedValues.Transfer ?: return null
        return listOf(
            observation.family,
            observation.ref.slot,
            values.timestamp,
            values.sourceAccountId,
            values.targetAccountId,
            values.assetId,
            values.amount.toString(),
            values.description,
        )
    }

    override fun distance(
        a: MatchObservation,
        b: MatchObservation,
    ): Long = 0
}

/**
 * A bank re-export's drift: the same asset and amount, one shared end (the same source or the same target), times
 * within the policy's date tolerance and descriptions at least its similarity threshold alike. Never between a record
 * the source excluded and one it counts: that would count the movement once or not at all depending on which won.
 */
internal class FuzzyRule(
    private val policies: Map<String, DedupePolicy.FuzzyAllFields>,
) : PairRule {
    override val name = "fuzzy"
    override val joinsSameContainer = false

    override fun bucket(observation: MatchObservation): Any? {
        if (observation.family !in policies) return null
        val values = observation.content.values as? ObservedValues.Transfer ?: return null
        return listOf(observation.family, observation.ref.slot, values.assetId, values.amount.toString(), observation.excluded)
    }

    override fun distance(
        a: MatchObservation,
        b: MatchObservation,
    ): Long? {
        val policy = policies.getValue(a.family)
        val x = a.content.values as ObservedValues.Transfer
        val y = b.content.values as ObservedValues.Transfer
        val sharesEnd = x.sourceAccountId == y.sourceAccountId || x.targetAccountId == y.targetAccountId
        val apart = (x.timestamp - y.timestamp).absoluteValue
        return when {
            !sharesEnd -> null
            apart > policy.dateTolerance -> null
            StringSimilarity.similarity(x.description, y.description) < policy.similarityThreshold -> null
            else -> apart.inWholeSeconds
        }
    }
}

private fun ObservedValues.timestamp(): Instant =
    when (this) {
        is ObservedValues.Transfer -> timestamp
        is ObservedValues.Trade -> timestamp
    }

private fun secondsApart(
    a: ObservedValues,
    b: ObservedValues,
): Long = (a.timestamp() - b.timestamp()).absoluteValue.inWholeSeconds
