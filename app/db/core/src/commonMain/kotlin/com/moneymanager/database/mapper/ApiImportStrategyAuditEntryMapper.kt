@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForApiImportStrategy
import com.moneymanager.domain.model.ApiImportStrategyAuditEntry
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
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
            configJson = from.config_json,
            createdAt = Instant.fromEpochMilliseconds(from.created_at),
            updatedAt = Instant.fromEpochMilliseconds(from.updated_at),
        )
}
