package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationFacts
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.statementAccountId
import kotlin.time.Instant

/**
 * What this leg says, as an observation's content: its values and attributes, and the facts the rank-merge reads. Its
 * own end is the end its provenance's statement account is; a placeholder end is the one its
 * `unidentified-counterparty` attribute ([unidentifiedCounterpartyTypeId]) names.
 */
fun ExpandedTransfer.observationContent(
    approximateTime: Boolean,
    unidentifiedCounterpartyTypeId: AttributeTypeId?,
): ObservationContent =
    ObservationContent(
        values =
            ObservedValues.Transfer(
                timestamp = timestamp,
                description = description,
                sourceAccountId = sourceAccountId,
                targetAccountId = targetAccountId,
                assetId = ObservedAssetId(amount.asset.id.id),
                amount = amount.amount,
            ),
        attributes = attributes.observed(),
        facts =
            ObservationFacts(
                approximateTime = approximateTime,
                placeholderEnds =
                    attributes
                        .filter { it.typeId == unidentifiedCounterpartyTypeId }
                        .mapNotNullTo(mutableSetOf()) { it.value.placeholderEnd() },
                ownEnd = provenance.ownEnd(sourceAccountId, targetAccountId),
            ),
    )

/** What this conversion says, as an observation's content. */
fun ExpandedTrade.observationContent(): ObservationContent =
    tradeContent(timestamp, description, accountId, fromAmount, accountId, toAmount, attributes, provenance)

/** What a trade a batch books says, as an observation's content; [intent] must carry every field of a create. */
fun ImportTradeIntent.observationContent(): ObservationContent =
    tradeContent(
        timestamp = requireNotNull(timestamp),
        description = requireNotNull(description),
        fromAccountId = requireNotNull(fromAccountId),
        from = requireNotNull(fromAmount),
        toAccountId = requireNotNull(toAccountId),
        to = requireNotNull(toAmount),
        attributes = attributes,
        provenance = source,
    )

private fun tradeContent(
    timestamp: Instant,
    description: String,
    fromAccountId: AccountId,
    from: Money,
    toAccountId: AccountId,
    to: Money,
    attributes: List<NewAttribute>,
    provenance: Source,
): ObservationContent =
    ObservationContent(
        values =
            ObservedValues.Trade(
                timestamp = timestamp,
                description = description,
                fromAccountId = fromAccountId,
                fromAssetId = ObservedAssetId(from.asset.id.id),
                fromAmount = from.amount,
                toAccountId = toAccountId,
                toAssetId = ObservedAssetId(to.asset.id.id),
                toAmount = to.amount,
            ),
        attributes = attributes.observed(),
        facts = ObservationFacts(ownEnd = provenance.ownEnd(fromAccountId, toAccountId)),
    )

// Keyed like transfer_attribute: one value per (type, group), the last a batch names winning, as the writer's upsert does.
private fun List<NewAttribute>.observed(): Set<ObservationAttribute> =
    associateBy { it.typeId to it.groupKey }
        .values
        .mapTo(mutableSetOf()) { ObservationAttribute(it.typeId, it.groupKey, it.value) }

private fun String.placeholderEnd(): ObservationEnd? =
    when (this) {
        WellKnownIds.UNIDENTIFIED_SOURCE -> ObservationEnd.SOURCE
        WellKnownIds.UNIDENTIFIED_TARGET -> ObservationEnd.TARGET
        else -> null
    }

// A trade on one account (a conversion) has that account at both ends: neither is told apart as its own.
private fun Source.ownEnd(
    source: AccountId,
    target: AccountId,
): ObservationEnd? {
    val own = statementAccountId ?: return null
    return when {
        source == target -> null
        own == source -> ObservationEnd.SOURCE
        own == target -> ObservationEnd.TARGET
        else -> null
    }
}
