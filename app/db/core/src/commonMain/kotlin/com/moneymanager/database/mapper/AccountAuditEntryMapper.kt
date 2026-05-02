@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForAccount
import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSourceDetails
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceType
import tech.mappie.api.ObjectMappie
import kotlin.time.Instant

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

    val deviceInfo =
        when (source_platform_name) {
            "JVM" ->
                DeviceInfo.Jvm(
                    machineName = source_machine_name ?: "Unknown",
                    osName = source_os_name ?: "Unknown",
                )
            "Android" ->
                DeviceInfo.Android(
                    deviceMake = source_device_make ?: "Unknown",
                    deviceModel = source_device_model ?: "Unknown",
                )
            else -> null
        }

    val apiSource =
        if (source_api_session_id != null && source_api_request_id != null && source_api_json_path != null) {
            ApiSourceDetails(
                sessionId = ApiSessionId(source_api_session_id),
                requestId = ApiRequestId(source_api_request_id),
                jsonPath = JsonPath(source_api_json_path),
            )
        } else {
            null
        }

    return EntitySource(
        id = sourceId,
        entityType = EntityType.ACCOUNT,
        entityId = account_id,
        revisionId = revision_id,
        sourceType = SourceType.fromName(sourceTypeName),
        deviceId = deviceId,
        deviceInfo = deviceInfo,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        apiSource = apiSource,
    )
}
