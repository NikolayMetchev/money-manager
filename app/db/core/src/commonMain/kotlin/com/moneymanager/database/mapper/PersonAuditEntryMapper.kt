@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForPerson
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSourceDetails
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.PersonAuditEntry
import tech.mappie.api.ObjectMappie

object PersonAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForPerson, PersonAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForPerson): PersonAuditEntry =
        mapping {
            PersonAuditEntry::source fromValue from.toEntitySource()
        }
}

private fun SelectAuditHistoryForPerson.toEntitySource(): EntitySource? {
    val deviceInfo =
        auditDeviceInfo(
            platformName = source_platform_name,
            machineName = source_machine_name,
            osName = source_os_name,
            deviceMake = source_device_make,
            deviceModel = source_device_model,
        )

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

    return auditEntitySource(
        sourceId = source_id,
        sourceTypeId = source_type_id,
        sourceTypeName = source_type_name,
        deviceId = source_device_id,
        createdAt = source_created_at,
        entityType = EntityType.PERSON,
        entityId = person_id,
        revisionId = revision_id,
        deviceInfo = deviceInfo,
        apiSource = apiSource,
    )
}
