package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferSourceReadRepository

/**
 * Repository for managing transfer source records.
 * Tracks the provenance of each transfer modification.
 */
interface TransferSourceWriteRepository : TransferSourceReadRepository {
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
}
