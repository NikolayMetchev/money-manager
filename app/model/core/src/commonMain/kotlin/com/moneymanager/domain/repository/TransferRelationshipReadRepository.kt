package com.moneymanager.domain.repository

import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferRelationship
import kotlinx.coroutines.flow.Flow

interface TransferRelationshipReadRepository {
    /**
     * Gets all relationships touching a transfer, from either side of the pair (id1 or id2).
     * Results are ordered by relationship type name.
     */
    fun getByTransfer(transferId: TransferId): Flow<List<TransferRelationship>>

    /**
     * Batch lookup of all relationships touching any of [transferIds], from either side of the
     * pair — for callers that would otherwise issue one [getByTransfer] round trip per id, e.g.
     * the re-import planner walking the conduit chains of thousands of pass-through rows.
     */
    suspend fun getByTransfers(transferIds: Collection<TransferId>): List<TransferRelationship>
}
