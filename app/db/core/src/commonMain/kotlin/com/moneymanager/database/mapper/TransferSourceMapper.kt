@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.repository.DeviceRepositoryImpl
import com.moneymanager.database.sql.SelectAllByTransactionId
import com.moneymanager.database.sql.SelectAuditHistoryForTransferWithSource
import com.moneymanager.database.sql.SelectByTransactionIdAndRevision
import com.moneymanager.domain.model.CsvSourceDetails
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.TransferSource
import com.moneymanager.domain.model.csv.CsvImportId
import tech.mappie.api.ObjectMappie
import kotlin.uuid.Uuid

object TransferSourceFromRevisionMapper :
    ObjectMappie<SelectByTransactionIdAndRevision, TransferSource>(),
    IdConversions,
    InstantConversions,
    SourceTypeConversions {
    override fun map(from: SelectByTransactionIdAndRevision): TransferSource {
        val sourceType = toSourceType(from.sourceType)
        return mapping {
            TransferSource::transactionId fromValue toTransferId(from.transactionId)
            TransferSource::sourceType fromValue sourceType
            TransferSource::deviceId fromProperty from::device_id
            TransferSource::deviceInfo fromValue
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = from.platformName,
                    osName = from.osName,
                    machineName = from.machineName,
                    deviceMake = from.deviceMake,
                    deviceModel = from.deviceModel,
                )
            TransferSource::csvSource fromValue
                mapCsvSource(sourceType, from.csvImportId, from.csvRowIndex, from.csvFileName)
            TransferSource::createdAt fromValue toInstant(from.createdAt)
        }
    }
}

object TransferSourceFromTransactionIdMapper :
    ObjectMappie<SelectAllByTransactionId, TransferSource>(),
    IdConversions,
    InstantConversions,
    SourceTypeConversions {
    override fun map(from: SelectAllByTransactionId): TransferSource {
        val sourceType = toSourceType(from.sourceType)
        return mapping {
            TransferSource::transactionId fromValue toTransferId(from.transactionId)
            TransferSource::sourceType fromValue sourceType
            TransferSource::deviceId fromProperty from::device_id
            TransferSource::deviceInfo fromValue
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = from.platformName,
                    osName = from.osName,
                    machineName = from.machineName,
                    deviceMake = from.deviceMake,
                    deviceModel = from.deviceModel,
                )
            TransferSource::csvSource fromValue
                mapCsvSource(sourceType, from.csvImportId, from.csvRowIndex, from.csvFileName)
            TransferSource::createdAt fromValue toInstant(from.createdAt)
        }
    }
}

object TransferSourceFromAuditMapper :
    IdConversions,
    InstantConversions,
    SourceTypeConversions {
    fun map(from: SelectAuditHistoryForTransferWithSource): TransferSource {
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

        val sourceType = toSourceType(sourceTypeName)
        return TransferSource(
            id = sourceId,
            transactionId = toTransferId(from.id),
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
            createdAt = toInstant(sourceCreatedAt),
        )
    }
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
