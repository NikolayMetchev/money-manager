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

    /**
     * Applies [writes] in order in one database transaction, as [create] and [delete] would one by one. A
     * re-import rebuilds thousands of links, and a commit per link made that the slowest step.
     */
    suspend fun apply(writes: List<RelationshipWrite>)
}

/** One step of [RelationshipWriteRepository.apply]. */
sealed interface RelationshipWrite {
    data class Create(
        val typeId: RelationshipTypeId,
        val members: List<RelationshipMember>,
        val source: Source,
    ) : RelationshipWrite

    data class Delete(
        val id: RelationshipId,
    ) : RelationshipWrite
}
