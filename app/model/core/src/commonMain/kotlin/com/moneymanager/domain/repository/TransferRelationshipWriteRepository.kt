package com.moneymanager.domain.repository

import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.TransferId

interface TransferRelationshipWriteRepository : TransferRelationshipReadRepository {
    /** Inserts a relationship linking [id1] to [id2] of the given type. */
    suspend fun insert(
        id1: TransferId,
        id2: TransferId,
        typeId: RelationshipTypeId,
    )

    /** Deletes the relationship identified by the full key. */
    suspend fun delete(
        id1: TransferId,
        id2: TransferId,
        typeId: RelationshipTypeId,
    )
}
