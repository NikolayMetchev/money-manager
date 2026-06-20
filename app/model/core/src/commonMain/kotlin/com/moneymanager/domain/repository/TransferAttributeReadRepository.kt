package com.moneymanager.domain.repository

import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import kotlinx.coroutines.flow.Flow

interface TransferAttributeReadRepository {
    /**
     * Gets all current attributes for a transaction.
     * Results are ordered by attribute type name.
     */
    fun getByTransaction(transactionId: TransferId): Flow<List<TransferAttribute>>
}
