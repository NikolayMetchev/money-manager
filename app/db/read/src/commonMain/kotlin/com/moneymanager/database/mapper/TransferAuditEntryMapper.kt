package com.moneymanager.database.mapper

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.sql.audit.SelectAuditHistoryForTransfer
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TransferAuditEntry
import tech.mappie.api.ObjectMappie

object TransferAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForTransfer, TransferAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForTransfer): TransferAuditEntry =
        mapping {
            TransferAuditEntry::amount fromValue Money(BigInteger(from.amount), from.toAsset())
            TransferAuditEntry::source fromValue from.toSourceRecord()
        }
}

private fun SelectAuditHistoryForTransfer.toAsset() =
    AssetRowMapper.buildAsset(
        id = asset_id,
        code = asset_code,
        name = asset_name,
        scaleFactor = asset_scale_factor,
        kind = asset_kind,
    )

private fun SelectAuditHistoryForTransfer.toSourceRecord(): SourceRecord? =
    buildSourceRecord(
        SourceColumns(
            sourceId = source_id,
            sourceTypeName = source_type,
            deviceId = device_id,
            createdAt = source_created_at,
            entityType = EntityType.TRANSFER,
            entityId = transfer_id,
            revisionId = revision_id,
            detail =
                SourceDetailColumns(
                    platformName = source_platform_name,
                    osName = source_os_name,
                    machineName = source_machine_name,
                    deviceMake = source_device_make,
                    deviceModel = source_device_model,
                    csvImportId = source_csv_import_id,
                    csvRowIndex = source_csv_row_index,
                    csvFileName = source_csv_file_name,
                    qifImportId = source_qif_import_id,
                    qifRecordIndex = source_qif_record_index,
                    qifFileName = source_qif_file_name,
                    apiSessionId = source_api_session_id,
                    apiRequestId = source_api_request_id,
                    apiJsonPath = source_api_json_path,
                ),
        ),
    )
