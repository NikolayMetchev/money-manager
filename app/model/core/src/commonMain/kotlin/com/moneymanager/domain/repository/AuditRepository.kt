@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId

interface AuditRepository {
    /**
     * Gets the audit history for a specific transfer.
     * Returns entries ordered by audit timestamp descending (most recent first).
     *
     * @param transferId The ID of the transfer to get audit history for
     * @return List of audit entries for the transfer
     */
    suspend fun getAuditHistoryForTransfer(transferId: TransferId): List<TransferAuditEntry>
}
