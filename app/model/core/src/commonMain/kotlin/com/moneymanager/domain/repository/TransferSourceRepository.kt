@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferSource
import com.moneymanager.domain.model.csv.CsvImportId

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
     * @return The created source record
     */
    suspend fun recordManualSource(
        transactionId: TransferId,
        revisionId: Long,
        deviceInfo: DeviceInfo,
    ): TransferSource

    /**
     * Records that a transfer was created from a CSV import.
     *
     * @param transactionId The transfer's transaction ID
     * @param revisionId The revision of the transfer
     * @param csvImportId The ID of the CSV import
     * @param rowIndex The row index in the CSV file
     * @return The created source record
     */
    suspend fun recordCsvImportSource(
        transactionId: TransferId,
        revisionId: Long,
        csvImportId: CsvImportId,
        rowIndex: Long,
    ): TransferSource

    /**
     * Records sources for multiple transfers from a CSV import in batch.
     *
     * @param csvImportId The ID of the CSV import
     * @param sources List of (transactionId, revisionId, rowIndex) tuples
     */
    suspend fun recordCsvImportSourcesBatch(
        csvImportId: CsvImportId,
        sources: List<CsvImportSourceRecord>,
    )

    /**
     * Gets all sources for a specific transaction (across all revisions).
     *
     * @param transactionId The transaction ID
     * @return List of sources ordered by revision descending
     */
    suspend fun getSourcesForTransaction(transactionId: TransferId): List<TransferSource>

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
    ): TransferSource?

    /**
     * Records sources for multiple transfers from the sample data generator in batch.
     *
     * @param deviceInfo Device information from the platform
     * @param sources List of (transactionId, revisionId) tuples
     */
    suspend fun recordSampleGeneratorSourcesBatch(
        deviceInfo: DeviceInfo,
        sources: List<SampleGeneratorSourceRecord>,
    )

    /**
     * Records that a transfer was created from an API import.
     *
     * @param transactionId The transfer's transaction ID
     * @param revisionId The revision of the transfer
     * @param sessionId The ID of the API session
     * @param requestId The ID of the API request that produced this transfer
     * @param deviceInfo Device information from the platform
     * @return The created source record
     */
    suspend fun recordApiSource(
        transactionId: TransferId,
        revisionId: Long,
        sessionId: ApiSessionId,
        requestId: ApiRequestId,
        deviceInfo: DeviceInfo,
    ): TransferSource

    /**
     * Records sources for multiple transfers from an API import in batch.
     *
     * @param sessionId The ID of the API session
     * @param requestId The ID of the API request that produced these transfers
     * @param deviceInfo Device information from the platform
     * @param sources List of (transactionId, revisionId) tuples
     */
    suspend fun recordApiSourcesBatch(
        sessionId: ApiSessionId,
        requestId: ApiRequestId,
        deviceInfo: DeviceInfo,
        sources: List<ApiSourceRecord>,
    )
}

/**
 * Record for batch sample generator source recording.
 */
data class SampleGeneratorSourceRecord(
    val transactionId: TransferId,
    val revisionId: Long,
)

/**
 * Record for batch CSV import source recording.
 */
data class CsvImportSourceRecord(
    val transactionId: TransferId,
    val revisionId: Long,
    val rowIndex: Long,
)

/**
 * Record for batch API import source recording.
 */
data class ApiSourceRecord(
    val transactionId: TransferId,
    val revisionId: Long,
)
