@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.mapper.SourceColumns
import com.moneymanager.database.mapper.SourceDetailColumns
import com.moneymanager.database.mapper.buildSourceRecord
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.database.sql.entitySource.SelectAllTransferSourcesByTransaction
import com.moneymanager.database.sql.entitySource.SelectTransferSourceByRevision
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferSourceReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only implementation of TransferSourceReadRepository using SQLDelight.
 * Transfers are stored in the unified entity_source store as entity_type_id = 7 (TRANSFER),
 * keyed by the transfer id.
 */
class TransferSourceReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : TransferSourceReadRepository {
    private val selectQueries = database.entitySourceSelectQueries

    override suspend fun getSourcesForTransaction(transactionId: TransferId): List<SourceRecord> =
        withContext(Dispatchers.Default) {
            selectQueries
                .selectAllTransferSourcesByTransaction(transactionId.id)
                .executeAsList()
                .mapNotNull { it.toSourceRecord() }
        }

    override suspend fun getSourceByRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): SourceRecord? =
        withContext(Dispatchers.Default) {
            selectQueries
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
            detail =
                SourceDetailColumns(
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
            detail =
                SourceDetailColumns(
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
        ),
    )
