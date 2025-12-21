@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.repository.DeviceRepositoryImpl
import com.moneymanager.database.sql.SelectAllByTransactionId
import com.moneymanager.database.sql.SelectAuditHistoryForTransferWithSource
import com.moneymanager.database.sql.SelectByTransactionIdAndRevision
import com.moneymanager.domain.model.CsvSourceDetails
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferSource
import com.moneymanager.domain.model.csv.CsvImportId
import kotlin.time.Instant.Companion.fromEpochMilliseconds
import kotlin.uuid.Uuid

/**
 * Maps database query results to TransferSource domain model.
 */
object TransferSourceMapper {
    fun map(from: SelectByTransactionIdAndRevision): TransferSource {
        val sourceType = SourceType.fromName(from.sourceTypeName)
        return TransferSource(
            id = from.id,
            transactionId = TransferId(Uuid.parse(from.transactionId)),
            revisionId = from.revisionId,
            sourceType = sourceType,
            deviceId = from.device_id,
            deviceInfo =
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = from.platformName,
                    osName = from.osName,
                    machineName = from.machineName,
                    deviceMake = from.deviceMake,
                    deviceModel = from.deviceModel,
                ),
            csvSource = mapCsvSource(sourceType, from.csvImportId, from.csvRowIndex, from.csvFileName),
            createdAt = fromEpochMilliseconds(from.createdAt),
        )
    }

    fun map(from: SelectAllByTransactionId): TransferSource {
        val sourceType = SourceType.fromName(from.sourceTypeName)
        return TransferSource(
            id = from.id,
            transactionId = TransferId(Uuid.parse(from.transactionId)),
            revisionId = from.revisionId,
            sourceType = sourceType,
            deviceId = from.device_id,
            deviceInfo =
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = from.platformName,
                    osName = from.osName,
                    machineName = from.machineName,
                    deviceMake = from.deviceMake,
                    deviceModel = from.deviceModel,
                ),
            csvSource = mapCsvSource(sourceType, from.csvImportId, from.csvRowIndex, from.csvFileName),
            createdAt = fromEpochMilliseconds(from.createdAt),
        )
    }

    fun mapFromAuditQuery(from: SelectAuditHistoryForTransferWithSource): TransferSource {
        val sourceTypeName =
            from.source_type_name
                ?: throw IllegalArgumentException("source_type_name is null")
        val sourceId =
            from.source_id
                ?: throw IllegalArgumentException("source_id is null")
        val sourceCreatedAt =
            from.source_created_at
                ?: throw IllegalArgumentException("source_created_at is null")
        val sourcePlatformName =
            from.source_platform_name
                ?: throw IllegalArgumentException("source_platform_name is null")
        val sourceDeviceId =
            from.source_device_id
                ?: throw IllegalArgumentException("source_device_id is null")

        val sourceType = SourceType.fromName(sourceTypeName)
        return TransferSource(
            id = sourceId,
            transactionId = TransferId(Uuid.parse(from.id)),
            revisionId = from.revisionId,
            sourceType = sourceType,
            deviceId = sourceDeviceId,
            deviceInfo =
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = sourcePlatformName,
                    osName = from.source_os_name,
                    machineName = from.source_machine_name,
                    deviceMake = from.source_device_make,
                    deviceModel = from.source_device_model,
                ),
            csvSource =
                mapCsvSource(
                    sourceType,
                    from.source_csv_import_id,
                    from.source_csv_row_index,
                    from.source_csv_file_name,
                ),
            createdAt = fromEpochMilliseconds(sourceCreatedAt),
        )
    }

    private fun mapCsvSource(
        sourceType: SourceType,
        csvImportId: String?,
        csvRowIndex: Long?,
        csvFileName: String?,
    ): CsvSourceDetails? {
        if (sourceType != SourceType.CSV_IMPORT) return null
        return CsvSourceDetails(
            importId = csvImportId?.let { CsvImportId(Uuid.parse(it)) },
            rowIndex = csvRowIndex ?: 0,
            fileName = csvFileName,
        )
    }
}
