package com.moneymanager.domain.repository

import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferRelationship
import kotlinx.coroutines.flow.Flow

interface TransferRelationshipRepository {
    /**
     * Gets all relationships touching a transfer, from either side of the pair (id1 or id2).
     * Results are ordered by relationship type name.
     */
    fun getByTransfer(transferId: TransferId): Flow<List<TransferRelationship>>

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
