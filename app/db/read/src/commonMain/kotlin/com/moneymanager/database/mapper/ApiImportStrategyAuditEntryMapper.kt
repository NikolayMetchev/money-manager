package com.moneymanager.database.mapper

import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.database.sql.audit.SelectAuditHistoryForApiImportStrategy
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyAuditEntry
import kotlin.time.Instant
import kotlin.uuid.Uuid

object ApiImportStrategyAuditEntryMapper {
    fun map(from: SelectAuditHistoryForApiImportStrategy): ApiImportStrategyAuditEntry =
        ApiImportStrategyAuditEntry(
            id = from.id,
            auditTimestamp = Instant.fromEpochMilliseconds(from.audit_timestamp),
            auditType = AuditType.valueOf(from.audit_type.uppercase()),
            strategyId = ApiImportStrategyId(Uuid.parse(from.api_import_strategy_id)),
            revisionId = from.revision_id,
            name = from.name,
            config = ApiStrategyJsonCodec.decode(from.config_json),
            createdAt = Instant.fromEpochMilliseconds(from.created_at),
            updatedAt = Instant.fromEpochMilliseconds(from.updated_at),
            // The api_import_strategy_source table has no per-type detail rows (strategies are only
            // manually/system created), so the import-detail columns are absent here.
            source =
                buildSourceRecord(
                    SourceColumns(
                        sourceId = from.source_id,
                        sourceTypeName = from.source_type_name,
                        deviceId = from.source_device_id,
                        createdAt = from.source_created_at,
                        entityType = EntityType.API_IMPORT_STRATEGY,
                        entityId = 0,
                        revisionId = from.revision_id,
                        platformName = from.source_platform_name,
                        osName = from.source_os_name,
                        machineName = from.source_machine_name,
                        deviceMake = from.source_device_make,
                        deviceModel = from.source_device_model,
                    ),
                ),
        )
}
