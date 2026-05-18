@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectOwnershipAuditHistoryForAccount
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import tech.mappie.api.ObjectMappie

object OwnershipAuditHistoryForAccountMapper :
    ObjectMappie<SelectOwnershipAuditHistoryForAccount, PersonAccountOwnershipAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectOwnershipAuditHistoryForAccount): PersonAccountOwnershipAuditEntry =
        mapping {
            PersonAccountOwnershipAuditEntry::personFullName fromValue
                buildPersonFullName(
                    from.person_first_name,
                    from.person_middle_name,
                    from.person_last_name,
                )
            PersonAccountOwnershipAuditEntry::source fromValue from.toEntitySource()
        }
}

private fun SelectOwnershipAuditHistoryForAccount.toEntitySource(): EntitySource? {
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
        entityType = EntityType.PERSON_ACCOUNT_OWNERSHIP,
        entityId = person_account_ownership_id,
        revisionId = revision_id,
        sourceApiSessionId = source_api_session_id,
        sourceApiRequestId = source_api_request_id,
        sourceApiJsonPath = source_api_json_path,
    )
}
