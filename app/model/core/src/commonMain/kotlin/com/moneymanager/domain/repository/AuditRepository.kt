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

    /**
     * Gets the audit history for a specific transfer with source information.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param transferId The ID of the transfer to get audit history for
     * @return List of audit entries with source information
     */
    suspend fun getAuditHistoryForTransferWithSource(transferId: TransferId): List<TransferAuditEntry>
}
