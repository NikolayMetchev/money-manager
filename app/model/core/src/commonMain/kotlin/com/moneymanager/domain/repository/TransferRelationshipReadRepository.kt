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
}
