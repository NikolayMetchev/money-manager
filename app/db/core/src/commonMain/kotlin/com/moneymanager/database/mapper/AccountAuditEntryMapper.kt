@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForAccount
import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import tech.mappie.api.ObjectMappie

object AccountAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForAccount, AccountAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForAccount): AccountAuditEntry =
        mapping {
            AccountAuditEntry::source fromValue from.toEntitySource()
        }
}

private fun SelectAuditHistoryForAccount.toEntitySource(): EntitySource? {
    val sourceId = source_id ?: return null
    source_type_id ?: return null
    val sourceTypeName = source_type_name ?: return null
    val deviceId = source_device_id ?: return null
    val createdAt = source_created_at ?: return null

    return buildEntitySource(
        sourceId = sourceId,
        sourceTypeName = sourceTypeName,
        deviceId = deviceId,
        sourcePlatformName = source_platform_name,
        sourceMachineName = source_machine_name,
        sourceOsName = source_os_name,
        sourceDeviceMake = source_device_make,
        sourceDeviceModel = source_device_model,
        createdAt = createdAt,
        entityType = EntityType.ACCOUNT,
        entityId = account_id,
        revisionId = revision_id,
        sourceApiSessionId = source_api_session_id,
        sourceApiRequestId = source_api_request_id,
        sourceApiJsonPath = source_api_json_path,
        sourceCsvImportId = source_csv_import_id,
        sourceCsvRowIndex = source_csv_row_index,
        sourceCsvFileName = source_csv_file_name,
    )
}
