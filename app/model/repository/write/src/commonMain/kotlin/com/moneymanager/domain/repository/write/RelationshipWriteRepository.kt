package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.RelationshipReadRepository

interface RelationshipWriteRepository : RelationshipReadRepository {
    /**
     * Creates a relationship of [typeId] over [members] and records [source] as its provenance. Returns null
     * without writing when a relationship of [typeId] already has exactly these members in these roles.
     */
    suspend fun create(
        typeId: RelationshipTypeId,
        members: List<RelationshipMember>,
        source: Source,
    ): RelationshipId?

    /** Deletes the relationship; its members are untouched. */
    suspend fun delete(id: RelationshipId)
}
