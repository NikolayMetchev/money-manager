@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.SourceColumns
import com.moneymanager.database.mapper.buildSourceRecord
import com.moneymanager.database.sql.SelectAllByTransactionId
import com.moneymanager.database.sql.SelectByTransactionIdAndRevision
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of TransferSourceRepository using SQLDelight.
 * Manages transfer source records for tracking provenance.
 */
class TransferSourceRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    private val deviceRepository: DeviceRepository,
) : TransferSourceRepository {
    private val queries = database.transferSourceQueries

    override suspend fun recordManualSource(
        transactionId: TransferId,
        revisionId: Long,
        deviceInfo: DeviceInfo,
    ): Unit =
        withContext(Dispatchers.Default) {
            val deviceId = deviceRepository.getOrCreateDevice(deviceInfo)

            queries.insertManual(
                transaction_id = transactionId.id,
                revision_id = revisionId,
                device_id = deviceId.id,
            )
        }

    override suspend fun getSourcesForTransaction(transactionId: TransferId): List<SourceRecord> =
        withContext(Dispatchers.Default) {
            queries
                .selectAllByTransactionId(transactionId.id)
                .executeAsList()
                .mapNotNull { it.toSourceRecord() }
        }

    override suspend fun getSourceByRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): SourceRecord? =
        withContext(Dispatchers.Default) {
            queries
                .selectByTransactionIdAndRevision(transactionId.id, revisionId)
                .executeAsOneOrNull()
                ?.toSourceRecord()
        }
}

private fun SelectByTransactionIdAndRevision.toSourceRecord(): SourceRecord? =
    buildSourceRecord(
        SourceColumns(
            sourceId = id,
            sourceTypeName = source_type,
            deviceId = device_id,
            createdAt = created_at,
            entityType = EntityType.TRANSFER,
            entityId = transaction_id,
            revisionId = revision_id,
            platformName = platform_name,
            osName = os_name,
            machineName = machine_name,
            deviceMake = device_make,
            deviceModel = device_model,
            csvImportId = csv_import_id,
            csvRowIndex = csv_row_index,
            csvFileName = csv_file_name,
            qifImportId = qif_import_id,
            qifRecordIndex = qif_record_index,
            qifFileName = qif_file_name,
            apiSessionId = api_session_id,
            apiRequestId = api_request_id,
            apiJsonPath = api_json_path,
        ),
    )

private fun SelectAllByTransactionId.toSourceRecord(): SourceRecord? =
    buildSourceRecord(
        SourceColumns(
            sourceId = id,
            sourceTypeName = source_type,
            deviceId = device_id,
            createdAt = created_at,
            entityType = EntityType.TRANSFER,
            entityId = transaction_id,
            revisionId = revision_id,
            platformName = platform_name,
            osName = os_name,
            machineName = machine_name,
            deviceMake = device_make,
            deviceModel = device_model,
            csvImportId = csv_import_id,
            csvRowIndex = csv_row_index,
            csvFileName = csv_file_name,
            qifImportId = qif_import_id,
            qifRecordIndex = qif_record_index,
            qifFileName = qif_file_name,
            apiSessionId = api_session_id,
            apiRequestId = api_request_id,
            apiJsonPath = api_json_path,
        ),
    )
