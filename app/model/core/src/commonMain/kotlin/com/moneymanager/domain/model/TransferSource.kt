@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import com.moneymanager.domain.model.csv.CsvImportId
import kotlin.time.Instant

/**
 * Represents the provenance (source) of a transfer audit entry.
 * Each audit entry (INSERT, UPDATE, DELETE) tracks how that change was initiated.
 *
 * @property id Unique identifier for this source record
 * @property transactionId The transaction this source is associated with
 * @property revisionId The revision of the transfer when this source was recorded
 * @property sourceType The type of source (MANUAL, CSV_IMPORT, API)
 * @property deviceId Database ID of the device that performed this action
 * @property deviceInfo Device information from the platform that initiated the action
 * @property csvSource CSV-specific source details (non-null only for CSV_IMPORT sources)
 * @property apiSource API-specific source details (non-null only for API sources)
 * @property createdAt When this source record was created
 */
data class TransferSource(
    val id: Long,
    val transactionId: TransferId,
    val revisionId: Long,
    val sourceType: SourceType,
    val deviceId: Long,
    val deviceInfo: DeviceInfo,
    val csvSource: CsvSourceDetails?,
    val apiSource: ApiSourceDetails?,
    val createdAt: Instant,
)

/**
 * Details specific to CSV import sources.
 *
 * @property importId The ID of the CSV import (may be null if import was deleted)
 * @property rowIndex The row index from the original CSV file
 * @property fileName The original file name (null if import was deleted)
 */
data class CsvSourceDetails(
    val importId: CsvImportId?,
    val rowIndex: Long,
    val fileName: String?,
)

/**
 * Details specific to API import sources.
 *
 * @property sessionId The ID of the API session (may be null if session was deleted)
 * @property requestId The ID of the API request that produced this transfer (may be null if request was deleted)
 */
data class ApiSourceDetails(
    val sessionId: ApiSessionId?,
    val requestId: ApiRequestId?,
)
