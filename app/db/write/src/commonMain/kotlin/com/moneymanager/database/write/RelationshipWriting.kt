package com.moneymanager.database.write

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.Source
import kotlin.time.Clock

/**
 * Creates a relationship of [typeId] over [members] (transaction id to role) and records [source] as its
 * provenance, at revision 1. Skips the write and returns null when a relationship of [typeId] already has
 * exactly these members in these roles, so re-running an import never doubles a link.
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
    val wanted = members.map { (id, role) -> id to role.id }.toSet()
    val exists =
        relationshipSelectQueries
            .selectMembersOfTypeContaining(typeId, members.first().first)
            .awaitAsList()
            .groupBy({ it.relationship_id }, { it.transaction_id to it.role })
            .values
            .any { it.toSet() == wanted }
    if (exists) return null
    relationshipWriteQueries.insertRelationship(typeId, Clock.System.now().toEpochMilliseconds())
    val relationshipId = relationshipWriteQueries.lastInsertedId().awaitAsOne()
    members.forEach { (transactionId, role) -> relationshipWriteQueries.insertMember(relationshipId, transactionId, role.id) }
    recordSource(deviceId, EntityType.RELATIONSHIP, relationshipId, RELATIONSHIP_REVISION, source)
    return relationshipId
}

/** Relationships are created and deleted, never revised, so their provenance is always at revision 1. */
const val RELATIONSHIP_REVISION: Long = 1
