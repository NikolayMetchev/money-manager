@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.repository.DeviceRepositoryImpl
import com.moneymanager.database.sql.SelectAllByTransactionId
import com.moneymanager.database.sql.SelectAuditHistoryForTransfer
import com.moneymanager.database.sql.SelectByTransactionIdAndRevision
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSourceDetails
import com.moneymanager.domain.model.CsvSourceDetails
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.JsonPath
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
        val sourceType = toSourceType(from.source_type)
        return mapping {
            TransferSource::deviceInfo fromValue from.toDeviceInfo()
            TransferSource::csvSource fromValue mapCsvSource(sourceType, from.csv_import_id, from.csv_row_index, from.csv_file_name)
            TransferSource::apiSource fromValue mapApiSource(sourceType, from.api_session_id, from.api_request_id, from.api_json_path)
        }
    }
}

object TransferSourceFromTransactionIdMapper :
    ObjectMappie<SelectAllByTransactionId, TransferSource>(),
    IdConversions,
    InstantConversions,
    SourceTypeConversions {
    override fun map(from: SelectAllByTransactionId): TransferSource {
        val sourceType = toSourceType(from.source_type)
        return mapping {
            TransferSource::deviceInfo fromValue from.toDeviceInfo()
            TransferSource::csvSource fromValue mapCsvSource(sourceType, from.csv_import_id, from.csv_row_index, from.csv_file_name)
            TransferSource::apiSource fromValue mapApiSource(sourceType, from.api_session_id, from.api_request_id, from.api_json_path)
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
            TransferSource::apiSource fromValue
                mapApiSource(
                    sourceType,
                    from.source_api_session_id,
                    from.source_api_request_id,
                    from.source_api_json_path,
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

private fun mapApiSource(
    sourceType: SourceType,
    apiSessionId: Long?,
    apiRequestId: Long?,
    apiJsonPath: String?,
): ApiSourceDetails? {
    if (sourceType != SourceType.API) return null
    return ApiSourceDetails(
        sessionId = ApiSessionId(checkNotNull(apiSessionId)),
        requestId = ApiRequestId(checkNotNull(apiRequestId)),
        jsonPath = JsonPath(checkNotNull(apiJsonPath)),
    )
}

private fun SelectByTransactionIdAndRevision.toDeviceInfo(): DeviceInfo =
    transferDeviceInfo(
        platformName = platform_name,
        osName = os_name,
        machineName = machine_name,
        deviceMake = device_make,
        deviceModel = device_model,
    )

private fun SelectAllByTransactionId.toDeviceInfo(): DeviceInfo =
    transferDeviceInfo(
        platformName = platform_name,
        osName = os_name,
        machineName = machine_name,
        deviceMake = device_make,
        deviceModel = device_model,
    )

private fun transferDeviceInfo(
    platformName: String,
    osName: String?,
    machineName: String?,
    deviceMake: String?,
    deviceModel: String?,
): DeviceInfo =
    DeviceRepositoryImpl.createDeviceInfo(
        platformName = platformName,
        osName = osName,
        machineName = machineName,
        deviceMake = deviceMake,
        deviceModel = deviceModel,
    )
