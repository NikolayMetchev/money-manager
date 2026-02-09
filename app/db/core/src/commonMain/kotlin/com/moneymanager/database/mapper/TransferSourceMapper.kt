@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.repository.DeviceRepositoryImpl
import com.moneymanager.database.sql.SelectAllByTransactionId
import com.moneymanager.database.sql.SelectAuditHistoryForTransfer
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
            TransferSource::deviceInfo fromValue
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = from.platform_name,
                    osName = from.os_name,
                    machineName = from.machine_name,
                    deviceMake = from.device_make,
                    deviceModel = from.device_model,
                )
            TransferSource::csvSource fromValue
                mapCsvSource(toSourceType(from.source_type), from.csv_import_id, from.csv_row_index, from.csv_file_name)
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
            TransferSource::deviceInfo fromValue
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = from.platform_name,
                    osName = from.os_name,
                    machineName = from.machine_name,
                    deviceMake = from.device_make,
                    deviceModel = from.device_model,
                )
            TransferSource::csvSource fromValue
                mapCsvSource(toSourceType(from.source_type), from.csv_import_id, from.csv_row_index, from.csv_file_name)
        }
    }
}

object TransferSourceFromAuditMapper :
    ObjectMappie<SelectAuditHistoryForTransfer, TransferSource>(),
    IdConversions,
    InstantConversions,
    SourceTypeConversions {
    override fun map(from: SelectAuditHistoryForTransfer): TransferSource {
        val sourceType = toSourceType(from.source_type!!)
        return mapping {
            TransferSource::id fromValue from.source_id!!
            TransferSource::transactionId fromValue toTransferId(from.transfer_id)
            TransferSource::sourceType fromValue sourceType
            TransferSource::deviceId fromValue from.device_id!!
            TransferSource::deviceInfo fromValue
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = from.source_platform_name!!,
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
            TransferSource::createdAt fromValue toInstant(from.source_created_at!!)
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
