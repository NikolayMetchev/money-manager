@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectOwnershipAuditHistoryForAccount
import com.moneymanager.domain.model.EntitySource
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

private fun SelectOwnershipAuditHistoryForAccount.toEntitySource(): EntitySource? =
    buildPersonAccountOwnershipEntitySource(
        sourceId = source_id,
        sourceTypeId = source_type_id,
        sourceTypeName = source_type_name,
        sourceDeviceId = source_device_id,
        sourceCreatedAt = source_created_at,
        sourcePlatformName = source_platform_name,
        sourceMachineName = source_machine_name,
        sourceOsName = source_os_name,
        sourceDeviceMake = source_device_make,
        sourceDeviceModel = source_device_model,
        entityId = person_account_ownership_id,
        revisionId = revision_id,
        sourceApiSessionId = source_api_session_id,
        sourceApiRequestId = source_api_request_id,
        sourceApiJsonPath = source_api_json_path,
    )
