package com.moneymanager.database.write

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import kotlin.time.Clock

/**
 * Creates a relationship of [typeId] over [members] (transaction id to role) and records [source] as its
 * provenance, at revision 1. Skips the write and returns null when a relationship of [typeId] already has
 * exactly these members in these roles, so re-running an import never doubles a link. A reconciliation match is
 * the one type where a transaction is in at most one relationship: a new match that overlaps stored ones replaces
 * them with the union of their members.
 *
 * Call it inside the caller's open transaction, like [recordSource].
 */
suspend fun MoneyManagerDatabaseWrapper.insertRelationship(
    deviceId: DeviceId,
    typeId: Long,
    members: List<Pair<Long, RelationshipRole>>,
    source: Source,
): Long? {
    require(members.map { it.first }.toSet().size == members.size) { "a transaction is a member at most once" }
    require(members.size >= 2) { "a relationship relates at least two transactions" }
    val exclusive = typeId == WellKnownIds.RECONCILIATION_MATCH_RELATIONSHIP_TYPE_ID
    val existing =
        // Only a match can overlap a relationship through any member; the pairwise types key off the first.
        (if (exclusive) members else members.take(1))
            .flatMap { relationshipSelectQueries.selectMembersOfTypeContaining(typeId, it.first).awaitAsList() }
            .distinctBy { it.relationship_id to it.transaction_id }
            .groupBy({ it.relationship_id }, { it.transaction_id to it.role })
            .mapValues { it.value.toSet() }
    val wanted = members.map { (id, role) -> id to role.id }.toSet()
    // A match joins every transaction it touches, so a new one that overlaps stored matches replaces them with
    // the union (and one that adds nothing is a no-op).
    val merged =
        if (exclusive) {
            val union = (existing.values.flatten() + wanted).associate { it }
            if (existing.size == 1 &&
                existing.values
                    .single()
                    .map { it.first }
                    .containsAll(union.keys)
            ) {
                return null
            }
            union.map { (id, role) -> id to RelationshipRole.entries.first { it.id == role } }
        } else {
            if (existing.values.any { it == wanted }) return null
            members
        }
    if (exclusive) existing.keys.forEach { relationshipWriteQueries.deleteById(it) }
    relationshipWriteQueries.insertRelationship(typeId, Clock.System.now().toEpochMilliseconds())
    val relationshipId = relationshipWriteQueries.lastInsertedId().awaitAsOne()
    merged.forEach { (transactionId, role) -> relationshipWriteQueries.insertMember(relationshipId, transactionId, role.id) }
    recordSource(deviceId, EntityType.RELATIONSHIP, relationshipId, RELATIONSHIP_REVISION, source)
    return relationshipId
}

/** Relationships are created and deleted, never revised, so their provenance is always at revision 1. */
const val RELATIONSHIP_REVISION: Long = 1
