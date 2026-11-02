package com.moneymanager.domain.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A typed relationship between any number of transactions ([TransferId]s and [TradeId]s alike). Who made it
 * is its provenance, an `entity_source` row of [EntityType.RELATIONSHIP], like any other entity's.
 *
 * The built-in types are directed pairs: one [RelationshipRole.PRIMARY] member (the owning transaction: the
 * reconciled duplicate, the main transaction, the funding leg) and one [RelationshipRole.RELATED] member (the
 * original, the fee, the spend leg). [primary] and [related] read those two ends.
 */
data class Relationship(
    val id: RelationshipId,
    val relationshipType: RelationshipType,
    val members: List<RelationshipMember>,
) {
    val primary: TransactionId? get() = members.firstOrNull { it.role == RelationshipRole.PRIMARY }?.transactionId
    val related: TransactionId? get() = members.firstOrNull { it.role == RelationshipRole.RELATED }?.transactionId

    /**
     * The (primary, related) ends of a directed pair whose two ends are both transfers, as every built-in
     * pairwise type links; null for any other shape.
     */
    val transferPair: Pair<TransferId, TransferId>?
        get() {
            val from = primary as? TransferId ?: return null
            val to = related as? TransferId ?: return null
            return from to to
        }

    /** The member ids other than [id]: what this relationship relates [id] to. */
    fun othersThan(id: TransactionId): List<TransactionId> = members.map { it.transactionId }.filter { it.id != id.id }
}

data class RelationshipMember(
    /** A [TransferId] or a [TradeId], whichever table the member's id belongs to. */
    val transactionId: TransactionId,
    val role: RelationshipRole,
)

/** What a member is to its relationship; stored as `relationship_member.role`. */
enum class RelationshipRole(
    val id: Long,
) {
    /** The owning end of a directed pair. */
    PRIMARY(0),

    /** The end a directed pair points at. */
    RELATED(1),

    /** A member of an undirected relationship. */
    MEMBER(2),
    ;

    companion object {
        fun fromId(id: Long): RelationshipRole = entries.first { it.id == id }
    }
}

@Serializable
@JvmInline
value class RelationshipId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

/**
 * A directed pair relationship to be created during import, before the owning transfer's id exists. The
 * owning transfer becomes the [RelationshipRole.PRIMARY] member and [relatedTransferId] the
 * [RelationshipRole.RELATED] one; [relatedTransferId] may be an existing transfer or the temporary id of one
 * created in the same batch.
 */
data class NewRelationship(
    val relatedTransferId: TransferId,
    val typeId: RelationshipTypeId,
)
