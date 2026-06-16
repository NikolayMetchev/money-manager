package com.moneymanager.domain.repository

import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TransferId

/**
 * Repository for managing transfer source records.
 * Tracks the provenance of each transfer modification.
 */
interface TransferSourceRepository {
    /**
     * Records that a transfer was created/modified manually.
     *
     * @param transactionId The transfer's transaction ID
     * @param revisionId The revision of the transfer
     * @param deviceInfo Device information from the platform
     */
    suspend fun recordManualSource(
        transactionId: TransferId,
        revisionId: Long,
        deviceInfo: DeviceInfo,
    )

    /**
     * Gets all sources for a specific transaction (across all revisions).
     *
     * @param transactionId The transaction ID
     * @return List of sources ordered by revision descending
     */
    suspend fun getSourcesForTransaction(transactionId: TransferId): List<SourceRecord>

    /**
     * Gets the source for a specific revision of a transaction.
     *
     * @param transactionId The transaction ID
     * @param revisionId The revision ID
     * @return The source or null if not found
     */
    suspend fun getSourceByRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): SourceRecord?
}
