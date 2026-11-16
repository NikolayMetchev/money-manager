package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import kotlin.time.Instant

/**
 * A pass-through chain whose conduit [conduitIndex] already has its own statement imported: an existing
 * placeholder leg ([statementLegId], e.g. a PayPal export's "General Credit Card Deposit", which cannot
 * name the card) records the same money arriving in (or, for a refund, leaving) the conduit as the chain's
 * movement into it — the main transfer for conduit 0, spend leg `conduitIndex - 1` otherwise. The engine
 * links that movement to [statementLegId] (`reconciled`), and creates every spend leg from the conduit
 * onward excluded: those legs are the card statement's guess at what the conduit did with the money, and
 * the conduit's statement records what it actually did (the real payee, a currency conversion first, …).
 */
data class ConduitStatementMatch(
    val conduitIndex: Int,
    val statementLegId: TransferId,
    val relationshipTypeId: RelationshipTypeId,
    val exclusionTypeId: AttributeTypeId,
)

/** What an expanded leg links to: a transfer already stored, or another leg expanded from the same batch. */
sealed interface LegLinkTarget {
    data class Existing(
        val id: TransferId,
    ) : LegLinkTarget

    data class Expanded(
        val key: ObservationKey,
    ) : LegLinkTarget
}

/** A relationship from an expanded leg (the primary) to [target] (the related member). */
data class LegLink(
    val target: LegLinkTarget,
    val typeId: RelationshipTypeId,
)

/**
 * One transfer to import, with what the match step decided about it, ready to expand. [transfer]'s accounts are
 * resolved ([AccountRef.Existing]).
 *
 * @property conduitStatement Set when its chain runs through a conduit whose own statement is already imported.
 * @property supersedesLegs True when its chain's movement into a conduit was reconciled away while it superseded
 *   another record's onward legs: this record is then the record of what the conduit did with the money, so its
 *   spend legs stay counted even when the row itself is excluded.
 * @property reversals Per spend-leg index, the earlier spend leg that leg reverses.
 */
data class ExpansionRow(
    val transfer: ImportTransfer,
    val conduitStatement: ConduitStatementMatch? = null,
    val supersedesLegs: Boolean = false,
    val reversals: Map<Int, LegLink> = emptyMap(),
)

/** One transfer an observation says, with every id it needs except its own. */
data class ExpandedTransfer(
    val key: ObservationKey,
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
    val amount: Money,
    /** The provenance of the row (or of the fee's or chain's own row) the leg comes from. */
    val provenance: Source,
    val attributes: List<NewAttribute>,
    val links: List<LegLink>,
)

/** One trade an observation says (a conversion its own account settled a movement with). */
data class ExpandedTrade(
    val key: ObservationKey,
    val timestamp: Instant,
    val description: String,
    val accountId: AccountId,
    val fromAmount: Money,
    val toAmount: Money,
    val provenance: Source,
    val attributes: List<NewAttribute>,
)

/** Every leg one [ExpansionRow] expands into: its own movement first, then its fee, then its chain's spend legs. */
data class ExpandedRow(
    val legs: List<ExpandedTransfer>,
) {
    val main: ExpandedTransfer get() = legs.first()
}

/**
 * Expands what imports say into slot-keyed observations, without a database: a transfer's fee into a `fee` leg, a
 * pass-through chain into one `conduit/<n>` leg per conduit, a settled foreign amount into a `trade`. Everything the
 * engine expands itself while it writes happens here, so a sync can derive what a source says before it touches
 * anything; the engine writes what this returns.
 *
 * Keys come only from the records (see [ObservationKey]), so the same records expand to the same keys in any order.
 */
object ObservationExpander {
    /**
     * Expands [rows] into transfers, one [ExpandedRow] per row in order. [relationshipTypeIds] resolves every type a
     * [BatchRelationship] names; a batch relationship to a row not in [rows] is dropped, as is one whose type is
     * missing.
     */
    fun expandTransfers(
        rows: List<ExpansionRow>,
        relationshipTypeIds: Map<String, RelationshipTypeId>,
    ): List<ExpandedRow> {
        val keys = rows.map { it.transfer.observationKey() }
        val mainKeyByRowKey = rows.zip(keys).mapNotNull { (row, key) -> row.transfer.rowKey?.let { it to key } }.toMap()
        val expanded = rows.mapIndexed { index, row -> expand(row, keys[index], mainKeyByRowKey, relationshipTypeIds) }
        // An observation is unique per (owner, slot): two legs of one slot would be one observation overwriting another.
        expanded
            .flatMap { row -> row.legs.map { it.key } }
            .groupingBy { it }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.let { throw IllegalArgumentException("Two transfers fill one observation slot: ${it.key}") }
        return expanded
    }

    /** The trade [transfer]'s own account settled it with, when it reports a foreign amount; null otherwise. */
    fun expandConversion(transfer: ImportTransfer): ExpandedTrade? {
        val conversion = transfer.conversion ?: return null
        val amount = requireNotNull(transfer.amount)
        val account = (if (conversion.incoming) transfer.toAccount else transfer.fromAccount).requireId()
        val (from, to) = if (conversion.incoming) amount to conversion.settled else conversion.settled to amount
        return ExpandedTrade(
            key = ObservationKey(transfer.observationOwner(), ObservationSlot.TRADE),
            timestamp = requireNotNull(transfer.timestamp),
            description = "Convert ${from.asset.code} to ${to.asset.code}",
            accountId = account,
            fromAmount = from,
            toAmount = to,
            provenance = transfer.source.forRow(requireNotNull(transfer.rowKey)),
            // A conversion of money that was never spent mustn't move balances either.
            attributes = transfer.attributes.filter { it.typeId.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID },
        )
    }

    private fun expand(
        row: ExpansionRow,
        mainKey: ObservationKey,
        mainKeyByRowKey: Map<ImportRowKey, ObservationKey>,
        relationshipTypeIds: Map<String, RelationshipTypeId>,
    ): ExpandedRow {
        val t = row.transfer
        val rowKey = requireNotNull(t.rowKey)
        val timestamp = requireNotNull(t.timestamp)
        val fee = t.fee
        val passThrough = t.passThrough
        val feeKey = ObservationKey(mainKey.owner, ObservationSlot.FEE)
        val spendKeys = passThrough?.conduits?.indices?.map { ObservationKey(mainKey.owner, ObservationSlot.conduit(it)) }
        val conduitStatement = row.conduitStatement

        fun leg(
            key: ObservationKey,
            description: String,
            source: AccountRef?,
            target: AccountRef?,
            amount: Money,
            legRowKey: ImportRowKey?,
            attributes: List<NewAttribute>,
            links: List<LegLink>,
        ): ExpandedTransfer {
            val sourceId = source.requireId()
            val targetId = target.requireId()
            // A transfer must move between two distinct accounts (enforced by a DB CHECK). The CSV path collapses
            // degenerate pass-through chains upstream (collapsePassThroughChain); this guards the invariant for
            // every producer with a clear error instead of an opaque SQLite failure.
            require(sourceId != targetId) {
                "Refusing to create a self-transfer on account ${sourceId.id} for \"$description\""
            }
            return ExpandedTransfer(
                key = key,
                timestamp = timestamp,
                description = description,
                sourceAccountId = sourceId,
                targetAccountId = targetId,
                amount = amount,
                provenance = t.source.forRow(legRowKey ?: rowKey),
                attributes = attributes,
                links = links,
            )
        }

        val mainLinks =
            buildList {
                t.relationships.forEach { add(LegLink(LegLinkTarget.Existing(it.relatedTransferId), it.typeId)) }
                if (fee != null) add(LegLink(LegLinkTarget.Expanded(feeKey), fee.relationshipTypeId))
                if (passThrough != null && spendKeys != null) {
                    add(LegLink(LegLinkTarget.Expanded(spendKeys.first()), passThrough.relationshipTypeId))
                }
                conduitStatement?.takeIf { it.conduitIndex == 0 }?.let {
                    add(LegLink(LegLinkTarget.Existing(it.statementLegId), it.relationshipTypeId))
                }
                t.batchRelationships.forEach { br ->
                    val target = mainKeyByRowKey[br.relatedRowKey]
                    val typeId = relationshipTypeIds[br.typeName]
                    if (target != null && typeId != null) add(LegLink(LegLinkTarget.Expanded(target), typeId))
                }
            }
        val legs = mutableListOf<ExpandedTransfer>()
        legs += leg(mainKey, t.description, t.fromAccount, t.toAccount, requireNotNull(t.amount), rowKey, t.attributes, mainLinks)

        // Legs derived from an excluded movement (e.g. a cancelled/deleted row) are excluded with it, or its fee and
        // spend legs would still move balances.
        val exclusion = t.attributes.filter { it.typeId.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }
        val spendExclusion = exclusion.takeUnless { row.supersedesLegs }.orEmpty()
        // The fee is a real movement out of the main transfer's account; counts in balances.
        if (fee != null) {
            legs += leg(feeKey, fee.description, fee.source, fee.target, fee.amount, fee.rowKey, exclusion, emptyList())
        }
        // The pass-through spend legs, one per adjacent pair of the chain (C1→C2, …, Cn→merchant), same amount as the
        // funding leg (the main transfer). Each movement links to the next leg via the pass-through relationship
        // (main→leg1→leg2→…), so every conduit nets to zero and the spend is counted once. Outgoing charge: funding
        // card -> C1, legs run towards the merchant. Incoming refund/cancellation: funding C1 -> card, each leg reversed.
        if (passThrough != null && spendKeys != null) {
            val nodes = passThrough.conduits + passThrough.merchantTarget
            for (legIndex in passThrough.conduits.indices) {
                val attributes =
                    when {
                        spendExclusion.isNotEmpty() -> {
                            spendExclusion
                        }

                        // Beyond a conduit whose own statement is imported: that statement is the record.
                        conduitStatement != null && legIndex >= conduitStatement.conduitIndex -> {
                            listOf(NewAttribute(conduitStatement.exclusionTypeId, "reconciled"))
                        }

                        else -> {
                            emptyList()
                        }
                    }
                val links =
                    buildList {
                        if (legIndex < spendKeys.lastIndex) {
                            add(LegLink(LegLinkTarget.Expanded(spendKeys[legIndex + 1]), passThrough.relationshipTypeId))
                        }
                        // The chain's movement into the conduit whose statement it reconciled with, and every leg the
                        // statement supersedes beyond it, link to that statement row: the exclusions stay explained.
                        if (conduitStatement != null && legIndex >= conduitStatement.conduitIndex - 1) {
                            add(LegLink(LegLinkTarget.Existing(conduitStatement.statementLegId), conduitStatement.relationshipTypeId))
                        }
                        row.reversals[legIndex]?.let(::add)
                    }
                legs +=
                    leg(
                        spendKeys[legIndex],
                        passThrough.spendDescriptions[legIndex],
                        if (passThrough.incoming) nodes[legIndex + 1] else nodes[legIndex],
                        if (passThrough.incoming) nodes[legIndex] else nodes[legIndex + 1],
                        passThrough.amount,
                        passThrough.rowKey,
                        attributes,
                        links,
                    )
            }
        }
        return ExpandedRow(legs)
    }

    private fun AccountRef?.requireId(): AccountId =
        when (this) {
            is AccountRef.Existing -> id
            is AccountRef.Local -> error("Unresolved account reference: $key")
            null -> error("Missing account reference")
        }
}
