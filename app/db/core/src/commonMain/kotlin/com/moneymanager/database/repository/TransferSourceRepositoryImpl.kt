@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.SourceColumns
import com.moneymanager.database.mapper.buildSourceRecord
import com.moneymanager.database.recordSource
import com.moneymanager.database.sql.SelectAllTransferSourcesByTransaction
import com.moneymanager.database.sql.SelectTransferSourceByRevision
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of TransferSourceRepository using SQLDelight.
 * Manages transfer source records for tracking provenance. Transfers are stored in the unified
 * entity_source store as entity_type_id = 7 (TRANSFER), keyed by the transfer id.
 */
class TransferSourceRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    private val deviceRepository: DeviceRepository,
) : TransferSourceRepository {
    private val queries = database.entitySourceQueries

    override suspend fun recordManualSource(
        transactionId: TransferId,
        revisionId: Long,
        deviceInfo: DeviceInfo,
    ): Unit =
        withContext(Dispatchers.Default) {
            val deviceId = deviceRepository.getOrCreateDevice(deviceInfo)

            queries.recordSource(
                deviceId = deviceId,
                entityType = EntityType.TRANSFER,
                entityId = transactionId.id,
                revisionId = revisionId,
                source = Source.Manual,
            )
        }

    override suspend fun getSourcesForTransaction(transactionId: TransferId): List<SourceRecord> =
        withContext(Dispatchers.Default) {
            queries
                .selectAllTransferSourcesByTransaction(transactionId.id)
                .executeAsList()
                .mapNotNull { it.toSourceRecord() }
        }

    override suspend fun getSourceByRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): SourceRecord? =
        withContext(Dispatchers.Default) {
            queries
                .selectTransferSourceByRevision(transactionId.id, revisionId)
                .executeAsOneOrNull()
                ?.toSourceRecord()
        }
}

private fun SelectTransferSourceByRevision.toSourceRecord(): SourceRecord? =
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

private fun SelectAllTransferSourcesByTransaction.toSourceRecord(): SourceRecord? =
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
