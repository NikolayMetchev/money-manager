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
        return mapping {
            TransferSource::transactionId fromValue toTransferId(from.transactionId)
            TransferSource::deviceInfo fromValue
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = from.platformName,
                    osName = from.osName,
                    machineName = from.machineName,
                    deviceMake = from.deviceMake,
                    deviceModel = from.deviceModel,
                )
            TransferSource::csvSource fromValue
                mapCsvSource(toSourceType(from.sourceType), from.csvImportId, from.csvRowIndex, from.csvFileName)
        }
    }
}

object TransferSourceFromTransactionIdMapper :
    ObjectMappie<SelectAllByTransactionId, TransferSource>(),
    IdConversions,
    InstantConversions,
    SourceTypeConversions {
    override fun map(from: SelectAllByTransactionId): TransferSource {
        return mapping {
            TransferSource::transactionId fromValue toTransferId(from.transactionId)
            TransferSource::deviceInfo fromValue
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = from.platformName,
                    osName = from.osName,
                    machineName = from.machineName,
                    deviceMake = from.deviceMake,
                    deviceModel = from.deviceModel,
                )
            TransferSource::csvSource fromValue
                mapCsvSource(toSourceType(from.sourceType), from.csvImportId, from.csvRowIndex, from.csvFileName)
        }
    }
}

object TransferSourceFromAuditMapper :
    ObjectMappie<SelectAuditHistoryForTransferWithSource, TransferSource>(),
    IdConversions,
    InstantConversions,
    SourceTypeConversions {
    override fun map(from: SelectAuditHistoryForTransferWithSource): TransferSource {
        val sourceType = toSourceType(from.sourceType!!)
        return mapping {
            TransferSource::id fromValue from.sourceId!!
            TransferSource::transactionId fromValue toTransferId(from.id)
            TransferSource::sourceType fromValue sourceType
            TransferSource::deviceId fromValue from.deviceId!!
            TransferSource::deviceInfo fromValue
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = from.sourcePlatformName!!,
                    osName = from.source_os_name,
                    machineName = from.source_machine_name,
                    deviceMake = from.source_device_make,
                    deviceModel = from.source_device_model,
                )
            TransferSource::csvSource fromValue
                mapCsvSource(
                    sourceType,
                    from.source_csv_import_id,
                    from.source_csv_row_index,
                    from.source_csv_file_name,
                )
            TransferSource::createdAt fromValue toInstant(from.sourceCreatedAt!!)
        }
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
