package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
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

    /**
     * Reserves the relationships a CSV or API import made on [transactionIds], which a re-run vacates (#1510): creating one
     * again takes it back, and [deleteReserved] deletes the rest once the re-run has run.
     */
    suspend fun reserveImportMadeOf(transactionIds: Collection<TransactionId>)

    /** Deletes every relationship still reserved: what a re-run's records did not say again. */
    suspend fun deleteReserved()
}

/** One step of [RelationshipWriteRepository.apply]. */
sealed interface RelationshipWrite {
    data class Create(
        val typeId: RelationshipTypeId,
        val members: List<RelationshipMember>,
        val source: Source,
        val excludesRelated: Boolean = false,
        val pinObservations: Boolean = false,
    ) : RelationshipWrite

    data class Delete(
        val id: RelationshipId,
    ) : RelationshipWrite
}
