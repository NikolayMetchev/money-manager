@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForApiImportStrategy
import com.moneymanager.domain.model.ApiImportStrategyAuditEntry
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import kotlin.time.Instant
import kotlin.uuid.Uuid

object ApiImportStrategyAuditEntryMapper {
    fun map(from: SelectAuditHistoryForApiImportStrategy): ApiImportStrategyAuditEntry {
        val deviceInfo =
            auditDeviceInfo(
                platformName = from.source_platform_name,
                machineName = from.source_machine_name,
                osName = from.source_os_name,
                deviceMake = from.source_device_make,
                deviceModel = from.source_device_model,
            )
        val source =
            auditEntitySource(
                sourceId = from.source_id,
                sourceTypeName = from.source_type_name,
                deviceId = from.source_device_id,
                createdAt = from.source_created_at,
                entityType = EntityType.API_IMPORT_STRATEGY,
                entityId = 0,
                revisionId = from.revision_id,
                deviceInfo = deviceInfo,
            )
        return ApiImportStrategyAuditEntry(
            id = from.id,
            auditTimestamp = Instant.fromEpochMilliseconds(from.audit_timestamp),
            auditType = AuditType.valueOf(from.audit_type.uppercase()),
            strategyId = ApiImportStrategyId(Uuid.parse(from.api_import_strategy_id)),
            revisionId = from.revision_id,
            name = from.name,
            configJson = from.config_json,
            createdAt = Instant.fromEpochMilliseconds(from.created_at),
            updatedAt = Instant.fromEpochMilliseconds(from.updated_at),
            source = source,
        )
    }
}
