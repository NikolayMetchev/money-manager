@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.sql.audit.SelectAuditHistoryForTrade
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TradeAuditEntry
import tech.mappie.api.ObjectMappie

object TradeAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForTrade, TradeAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForTrade): TradeAuditEntry =
        mapping {
            TradeAuditEntry::fromAmount fromValue Money(BigInteger(from.from_amount), from.fromAsset())
            TradeAuditEntry::toAmount fromValue Money(BigInteger(from.to_amount), from.toAsset())
            TradeAuditEntry::source fromValue from.toSourceRecord()
        }
}

private fun SelectAuditHistoryForTrade.fromAsset() =
    AssetRowMapper.buildAsset(
        id = from_asset_id,
        code = from_asset_code,
        name = from_asset_name,
        scaleFactor = from_asset_scale_factor,
        kind = from_asset_kind,
    )

private fun SelectAuditHistoryForTrade.toAsset() =
    AssetRowMapper.buildAsset(
        id = to_asset_id,
        code = to_asset_code,
        name = to_asset_name,
        scaleFactor = to_asset_scale_factor,
        kind = to_asset_kind,
    )

private fun SelectAuditHistoryForTrade.toSourceRecord(): SourceRecord? =
    buildSourceRecord(
        SourceColumns(
            sourceId = source_id,
            sourceTypeName = source_type_name,
            deviceId = source_device_id,
            createdAt = source_created_at,
            entityType = EntityType.TRADE,
            entityId = trade_id,
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
