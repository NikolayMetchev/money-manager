package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AttributeTypeId
import kotlin.time.Duration

/**
 * What the observation matcher needs to know about one family of records (one CSV or API strategy, keyed by its id)
 * beyond what their observations say: how its records are deduplicated and reconciled, and which of their attributes
 * identify them.
 *
 * @property dedupePolicy the family's transfer policy, as its importer builds it.
 * @property tradePolicy the family's trade policy.
 * @property uniqueIdAttributeTypes the attribute types whose values together identify a record (a CSV strategy's
 *   unique-identifier columns, an API strategy's unique-identifier fields).
 * @property apiIdAttributeType the attribute type holding the provider's own transaction id, for an API family.
 * @property conversionWindows the reconcile window of each leg-group rule (by name) whose conversions are compared with
 *   another source's trades.
 */
data class MatchFamily(
    val dedupePolicy: DedupePolicy,
    val tradePolicy: TradeDedupePolicy = TradeDedupePolicy.ExactTupleOnly,
    val uniqueIdAttributeTypes: Set<AttributeTypeId> = emptySet(),
    val apiIdAttributeType: AttributeTypeId? = null,
    val conversionWindows: Map<String, Duration> = emptyMap(),
)
