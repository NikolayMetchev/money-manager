package com.moneymanager.domain.repository

import com.moneymanager.domain.model.TransferAttributeAuditEntry
import com.moneymanager.domain.model.TransferId
import kotlinx.coroutines.flow.Flow

interface TransferAttributeAuditRepository {
    /**
     * Gets all attribute audit entries for a specific transaction and revision.
     */
    fun getByTransactionAndRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): Flow<List<TransferAttributeAuditEntry>>

    /**
     * Gets all attribute audit entries for a transaction across all revisions.
     * Results are ordered by revision descending, then attribute type name.
     */
    fun getAllByTransaction(transactionId: TransferId): Flow<List<TransferAttributeAuditEntry>>
}
