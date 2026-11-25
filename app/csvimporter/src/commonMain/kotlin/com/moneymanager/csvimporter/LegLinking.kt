package com.moneymanager.csvimporter

import com.moneymanager.domain.model.rules.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.importengineapi.BatchRelationship
import com.moneymanager.importengineapi.ImportRowKey
import kotlin.time.Duration

/** Links each debit leg to the nearest credit leg of the same key within [window] (credits may be shared). */
internal fun pairDebitsToCredits(
    legs: List<CsvTransferWithAttributes>,
    window: Duration,
    relationshipTypeName: String,
): List<Pair<Long, BatchRelationship>> {
    val credits = legs.filter { it.groupLeg!!.side == GroupLegSide.CREDIT }
    return legs
        .filter { it.groupLeg!!.side == GroupLegSide.DEBIT }
        .mapNotNull { debit ->
            val match =
                credits
                    .filter { it.groupLeg!!.key == debit.groupLeg!!.key }
                    .minByOrNull { (it.transfer.timestamp - debit.transfer.timestamp).absoluteValue }
                    ?.takeIf { (it.transfer.timestamp - debit.transfer.timestamp).absoluteValue <= window }
                    ?: return@mapNotNull null
            debit.rowIndex to BatchRelationship(ImportRowKey.CsvRow(match.rowIndex), relationshipTypeName)
        }
}

/**
 * Links every member leg to the anchor leg that shares its key, as the relationship's primary member. A leg with a blank
 * key (the row names no group) links to nothing, and an anchor never links to itself.
 */
internal fun linkMembersToAnchors(
    legs: List<CsvTransferWithAttributes>,
    relationshipTypeName: String,
): List<Pair<Long, BatchRelationship>> {
    val (anchors, members) = legs.filter { it.groupLeg!!.key.isNotBlank() }.partition { it.groupLeg!!.funding }
    val anchorByKey = anchors.sortedBy { it.rowIndex }.groupBy { it.groupLeg!!.key }.mapValues { (_, group) -> group.first() }
    return members.mapNotNull { member ->
        val anchor = anchorByKey[member.groupLeg!!.key] ?: return@mapNotNull null
        member.rowIndex to BatchRelationship(ImportRowKey.CsvRow(anchor.rowIndex), relationshipTypeName)
    }
}

/**
 * Links each funding leg (from the funding leg) to the nearest unclaimed non-funding leg on the other side
 * with the same key within [window], one to one. Legs the source excluded (a refused deposit) record no
 * movement, so they pair with nothing.
 */
internal fun pairFundingLegs(
    legs: List<CsvTransferWithAttributes>,
    window: Duration,
    relationshipTypeName: String,
): List<Pair<Long, BatchRelationship>> {
    val counted = legs.filter { leg -> leg.attributes.none { (name, _) -> name == EXCLUDED_ATTRIBUTE_TYPE_NAME } }
    val (funding, others) = counted.partition { it.groupLeg!!.funding }
    val claimed = mutableSetOf<Long>()
    return funding.sortedBy { it.transfer.timestamp }.mapNotNull { leg ->
        val match =
            others
                .filter {
                    it.rowIndex !in claimed &&
                        it.groupLeg!!.key == leg.groupLeg!!.key &&
                        it.groupLeg.side != leg.groupLeg.side &&
                        (it.transfer.timestamp - leg.transfer.timestamp).absoluteValue <= window
                }.minByOrNull { (it.transfer.timestamp - leg.transfer.timestamp).absoluteValue }
                ?: return@mapNotNull null
        claimed += match.rowIndex
        leg.rowIndex to BatchRelationship(ImportRowKey.CsvRow(match.rowIndex), relationshipTypeName)
    }
}
