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
import com.moneymanager.domain.model.QifSourceDetails
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferSource
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId
import tech.mappie.api.ObjectMappie
import kotlin.time.Instant
import kotlin.uuid.Uuid

object TransferSourceFromRevisionMapper :
    ObjectMappie<SelectByTransactionIdAndRevision, TransferSource>(),
    SourceTypeConversions {
    override fun map(from: SelectByTransactionIdAndRevision): TransferSource {
        val sourceType = toSourceType(from.source_type)
        return from.toTransferSource(sourceType)
    }
}

object TransferSourceFromTransactionIdMapper :
    ObjectMappie<SelectAllByTransactionId, TransferSource>(),
    SourceTypeConversions {
    override fun map(from: SelectAllByTransactionId): TransferSource {
        val sourceType = toSourceType(from.source_type)
        return from.toTransferSource(sourceType)
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

private fun mapQifSource(
    sourceType: SourceType,
    qifImportId: String?,
    qifRecordIndex: Long?,
    qifFileName: String?,
): QifSourceDetails? {
    if (sourceType != SourceType.QIF_IMPORT) return null
    return QifSourceDetails(
        importId = qifImportId?.let { QifImportId(Uuid.parse(it)) },
        recordIndex = qifRecordIndex ?: 0,
        fileName = qifFileName,
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

private fun SelectByTransactionIdAndRevision.toTransferSource(sourceType: SourceType): TransferSource =
    transferSource(
        id = id,
        transactionId = transaction_id,
        revisionId = revision_id,
        sourceType = sourceType,
        deviceId = device_id,
        deviceInfo = toDeviceInfo(),
        csvImportId = csv_import_id,
        csvRowIndex = csv_row_index,
        csvFileName = csv_file_name,
        qifImportId = qif_import_id,
        qifRecordIndex = qif_record_index,
        qifFileName = qif_file_name,
        apiSessionId = api_session_id,
        apiRequestId = api_request_id,
        apiJsonPath = api_json_path,
        createdAt = created_at,
    )

private fun SelectAllByTransactionId.toTransferSource(sourceType: SourceType): TransferSource =
    transferSource(
        id = id,
        transactionId = transaction_id,
        revisionId = revision_id,
        sourceType = sourceType,
        deviceId = device_id,
        deviceInfo = toDeviceInfo(),
        csvImportId = csv_import_id,
        csvRowIndex = csv_row_index,
        csvFileName = csv_file_name,
        qifImportId = qif_import_id,
        qifRecordIndex = qif_record_index,
        qifFileName = qif_file_name,
        apiSessionId = api_session_id,
        apiRequestId = api_request_id,
        apiJsonPath = api_json_path,
        createdAt = created_at,
    )

private fun transferSource(
    id: Long,
    transactionId: Long,
    revisionId: Long,
    sourceType: SourceType,
    deviceId: Long,
    deviceInfo: DeviceInfo,
    csvImportId: String?,
    csvRowIndex: Long?,
    csvFileName: String?,
    qifImportId: String?,
    qifRecordIndex: Long?,
    qifFileName: String?,
    apiSessionId: Long?,
    apiRequestId: Long?,
    apiJsonPath: String?,
    createdAt: Long,
): TransferSource =
    TransferSource(
        id = id,
        transactionId = TransferId(transactionId),
        revisionId = revisionId,
        sourceType = sourceType,
        deviceId = deviceId,
        deviceInfo = deviceInfo,
        csvSource = mapCsvSource(sourceType, csvImportId, csvRowIndex, csvFileName),
        apiSource = mapApiSource(sourceType, apiSessionId, apiRequestId, apiJsonPath),
        qifSource = mapQifSource(sourceType, qifImportId, qifRecordIndex, qifFileName),
        createdAt = Instant.fromEpochMilliseconds(createdAt),
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
